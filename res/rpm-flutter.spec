Name:       ashell
Version:    1.4.0
Release:    0
Summary:    RPM package
License:    GPL-3.0
URL:        https://rustdesk.com
Vendor:     A Shell You contributors
Requires:   gtk3 libxcb libxdo libXfixes alsa-lib libva pam gstreamer1-plugins-base
Recommends: libayatana-appindicator-gtk3
Provides:   libdesktop_drop_plugin.so()(64bit), libdesktop_multi_window_plugin.so()(64bit), libfile_selector_linux_plugin.so()(64bit), libflutter_custom_cursor_plugin.so()(64bit), libflutter_linux_gtk.so()(64bit), libscreen_retriever_plugin.so()(64bit), libtray_manager_plugin.so()(64bit), liburl_launcher_linux_plugin.so()(64bit), libwindow_manager_plugin.so()(64bit), libwindow_size_plugin.so()(64bit), libtexture_rgba_renderer_plugin.so()(64bit)

# https://docs.fedoraproject.org/en-US/packaging-guidelines/Scriptlets/

%description
The best open-source remote desktop client software, written in Rust.

%prep
# we have no source, so nothing here

%build
# we have no source, so nothing here

# %global __python %{__python3}

%install

mkdir -p "%{buildroot}/usr/share/ashell" && cp -r ${HBB}/flutter/build/linux/x64/release/bundle/* -t "%{buildroot}/usr/share/ashell"
mkdir -p "%{buildroot}/usr/bin"
install -Dm 644 $HBB/res/ashell.service -t "%{buildroot}/usr/share/ashell/files"
install -Dm 644 $HBB/res/ashell.desktop -t "%{buildroot}/usr/share/ashell/files"
install -Dm 644 $HBB/res/ashell-link.desktop -t "%{buildroot}/usr/share/ashell/files"
install -Dm 644 $HBB/res/128x128@2x.png "%{buildroot}/usr/share/icons/hicolor/256x256/apps/ashell.png"
install -Dm 644 $HBB/res/scalable.svg "%{buildroot}/usr/share/icons/hicolor/scalable/apps/ashell.svg"

%files
/usr/share/ashell/*
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
ln -sf /usr/share/ashell/ashell /usr/bin/ashell
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
    rm /usr/bin/ashell || true
    rmdir /usr/lib/ashell || true
    rmdir /usr/local/ashell || true
    rmdir /usr/share/ashell || true
    rm /usr/share/applications/ashell.desktop || true
    rm /usr/share/applications/ashell-link.desktop || true
    update-desktop-database
  ;;
  1)
    # for upgrade
    rmdir /usr/lib/ashell || true
    rmdir /usr/local/ashell || true
  ;;
esac
