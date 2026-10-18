Name:       ashell
Version:    1.1.9
Release:    0
Summary:    RPM package
License:    GPL-3.0
Requires:   gtk3 libxcb1 xdotool libXfixes3 alsa-utils libXtst6 libva2 pam gstreamer-plugins-base gstreamer-plugin-pipewire
Recommends: libayatana-appindicator3-1

# https://docs.fedoraproject.org/en-US/packaging-guidelines/Scriptlets/

%description
The best open-source remote desktop client software, written in Rust.

%prep
# we have no source, so nothing here

%build
# we have no source, so nothing here

%global __python %{__python3}

%install
mkdir -p %{buildroot}/usr/bin/
mkdir -p %{buildroot}/usr/share/ashell/
mkdir -p %{buildroot}/usr/share/ashell/files/
mkdir -p %{buildroot}/usr/share/icons/hicolor/256x256/apps/
mkdir -p %{buildroot}/usr/share/icons/hicolor/scalable/apps/
install -m 755 $HBB/target/release/ashell %{buildroot}/usr/bin/ashell
install $HBB/libsciter-gtk.so %{buildroot}/usr/share/ashell/libsciter-gtk.so
install $HBB/res/ashell.service %{buildroot}/usr/share/ashell/files/
install $HBB/res/128x128@2x.png %{buildroot}/usr/share/icons/hicolor/256x256/apps/ashell.png
install $HBB/res/scalable.svg %{buildroot}/usr/share/icons/hicolor/scalable/apps/ashell.svg
install $HBB/res/ashell.desktop %{buildroot}/usr/share/ashell/files/
install $HBB/res/ashell-link.desktop %{buildroot}/usr/share/ashell/files/

%files
/usr/bin/ashell
/usr/share/ashell/libsciter-gtk.so
/usr/share/ashell/files/ashell.service
/usr/share/icons/hicolor/256x256/apps/ashell.png
/usr/share/icons/hicolor/scalable/apps/ashell.svg
/usr/share/ashell/files/ashell.desktop
/usr/share/ashell/files/ashell-link.desktop

%changelog
# let's skip this for now

%pre
# can do something for centos7
case "$1" in
  1)
    # for install
  ;;
  2)
    # for upgrade
    systemctl stop ashell || true
  ;;
esac

%post
cp /usr/share/ashell/files/ashell.service /etc/systemd/system/ashell.service
cp /usr/share/ashell/files/ashell.desktop /usr/share/applications/
cp /usr/share/ashell/files/ashell-link.desktop /usr/share/applications/
systemctl daemon-reload
systemctl enable ashell
systemctl start ashell
update-desktop-database

%preun
case "$1" in
  0)
    # for uninstall
    systemctl stop ashell || true
    systemctl disable ashell || true
    rm /etc/systemd/system/ashell.service || true
  ;;
  1)
    # for upgrade
  ;;
esac

%postun
case "$1" in
  0)
    # for uninstall
    rm /usr/share/applications/ashell.desktop || true
    rm /usr/share/applications/ashell-link.desktop || true
    update-desktop-database
  ;;
  1)
    # for upgrade
  ;;
esac
