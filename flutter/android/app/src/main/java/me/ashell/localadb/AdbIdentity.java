package me.ashell.localadb;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x509.TBSCertificate;
import org.bouncycastle.asn1.x509.Time;
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Date;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** A separate ADB identity, encrypted with an app-scoped AndroidKeyStore key, excluded from backup. */
final class AdbIdentity {
    private static final String ALIAS = "ashell.localadb.identity.v1";
    private static AdbIdentity cached;
    static boolean exists(Context context) {
        return new File(context.getNoBackupFilesDir(), "local-adb-identity.v1").exists();
    }

    /** Caller first disconnects every shared transport; no old key may be reused afterwards. */
    static synchronized void revoke(Context context) throws Exception {
        // libadb 3.1.1 has a process-wide SSLContext containing its first identity.
        // Keep this pinned compatibility reset isolated; never reset product crypto providers.
        Class<?> ssl = Class.forName("io.github.muntashirakon.adb.SslUtils");
        java.lang.reflect.Field field = ssl.getDeclaredField("sslContext");
        field.setAccessible(true);
        field.set(null, null);
        cached = null;
        AtomicFile file = new AtomicFile(new File(context.getNoBackupFilesDir(), "local-adb-identity.v1"));
        file.delete();
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        store.deleteEntry(ALIAS);
        if (file.getBaseFile().exists() || store.containsAlias(ALIAS)) {
            throw new IllegalStateException("Identity removal failed");
        }
    }
    final PrivateKey privateKey;
    final Certificate certificate;

    private AdbIdentity(PrivateKey privateKey, Certificate certificate) {
        // AdbConnection.close() destroys the key it receives, while upstream's SSLContext
        // caches that same key. The persistent identity owns its lifetime, not a connection.
        this.privateKey = new BorrowedRsaKey((RSAPrivateKey) privateKey);
        this.certificate = certificate;
    }

    private static final class BorrowedRsaKey implements RSAPrivateKey {
        private static final long serialVersionUID = 1L;
        private final RSAPrivateKey key;

        BorrowedRsaKey(RSAPrivateKey key) { this.key = key; }
        @Override public BigInteger getModulus() { return key.getModulus(); }
        @Override public BigInteger getPrivateExponent() { return key.getPrivateExponent(); }
        @Override public String getAlgorithm() { return key.getAlgorithm(); }
        @Override public String getFormat() { return key.getFormat(); }
        @Override public byte[] getEncoded() { return key.getEncoded(); }
        @Override public void destroy() { /* Owned by AdbIdentity for the app process lifetime. */ }
        @Override public boolean isDestroyed() { return false; }
    }

    static synchronized AdbIdentity load(Context context) throws Exception {
        if (cached != null) return cached;
        AtomicFile file = new AtomicFile(new File(context.getNoBackupFilesDir(), "local-adb-identity.v1"));
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        SecretKey wrappingKey = (SecretKey) keyStore.getKey(ALIAS, null);
        if (file.getBaseFile().exists()) {
            // Never silently replace a damaged identity: that would invalidate an existing pairing.
            if (wrappingKey == null) throw new IllegalStateException("ADB identity unavailable");
            byte[] stored = file.readFully();
            if (stored.length < 29 || stored.length > 32768) throw new IllegalStateException("Invalid identity");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey,
                    new GCMParameterSpec(128, Arrays.copyOfRange(stored, 0, 12)));
            byte[] plain = cipher.doFinal(stored, 12, stored.length - 12);
            try {
                JSONObject json = new JSONObject(new String(plain, StandardCharsets.UTF_8));
                byte[] encodedKey = Base64.decode(json.getString("key"), Base64.NO_WRAP);
                try {
                    PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encodedKey));
                    Certificate cert = CertificateFactory.getInstance("X.509").generateCertificate(
                            new ByteArrayInputStream(Base64.decode(json.getString("cert"), Base64.NO_WRAP)));
                    cached = new AdbIdentity(key, cert);
                } finally {
                    Arrays.fill(encodedKey, (byte) 0);
                }
            } finally {
                Arrays.fill(plain, (byte) 0);
            }
            return cached;
        }
        if (wrappingKey == null) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            wrappingKey = generator.generateKey();
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keys = generator.generateKeyPair();
        long now = System.currentTimeMillis();
        X500Name name = new X500Name("CN=Local ADB");
        // Use BC's ASN.1 types and Android's standard JCA signer, not a global crypto provider.
        AlgorithmIdentifier algorithm = new AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE);
        V3TBSCertificateGenerator certificateBuilder = new V3TBSCertificateGenerator();
        certificateBuilder.setSerialNumber(new ASN1Integer(new BigInteger(159, new SecureRandom()).add(BigInteger.ONE)));
        certificateBuilder.setSignature(algorithm);
        certificateBuilder.setIssuer(name);
        certificateBuilder.setSubject(name);
        certificateBuilder.setStartDate(new Time(new Date(now - 86400000L)));
        certificateBuilder.setEndDate(new Time(new Date(now + 315360000000L)));
        certificateBuilder.setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(keys.getPublic().getEncoded()));
        TBSCertificate tbs = certificateBuilder.generateTBSCertificate();
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keys.getPrivate());
        signer.update(tbs.getEncoded(ASN1Encoding.DER));
        byte[] encodedCertificate = new DERSequence(new ASN1Encodable[]{tbs, algorithm, new DERBitString(signer.sign())})
                .getEncoded(ASN1Encoding.DER);
        Certificate cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(encodedCertificate));
        byte[] encodedKey = keys.getPrivate().getEncoded();
        byte[] plain;
        try {
            plain = new JSONObject().put("key", Base64.encodeToString(encodedKey, Base64.NO_WRAP))
                    .put("cert", Base64.encodeToString(cert.getEncoded(), Base64.NO_WRAP))
                    .toString().getBytes(StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(encodedKey, (byte) 0);
        }
        FileOutputStream output = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey);
            output = file.startWrite();
            output.write(cipher.getIV());
            output.write(cipher.doFinal(plain));
            file.finishWrite(output);
        } catch (Exception e) {
            if (output != null) file.failWrite(output);
            throw e;
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
        cached = new AdbIdentity(keys.getPrivate(), cert);
        return cached;
    }
}
