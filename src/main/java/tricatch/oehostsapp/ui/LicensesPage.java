package tricatch.oehostsapp.ui;

import tricatch.oehostsapp.BuildInfo;

import javax.swing.*;
import java.awt.*;
import java.util.List;

import static tricatch.oehostsapp.ui.SettingsKit.*;

/** Settings → General → open source: the bundled libraries with their versions and licenses. */
final class LicensesPage {

    /** A bundled library: name, homepage, Maven coordinate (null: not a Maven library), license. */
    private record Lib(String name, String url, String coordinate, String license, Color bg, Color fg) {}

    // Badge colours of oeHub's info-page.css (.badge-apache / -mit / -bsd).
    static final Color[] APACHE = {new Color(0xdce8ff), new Color(0x1a3a7a)};
    static final Color[] MIT = {new Color(0xdff2b8), new Color(0x3a6b1a)};
    static final Color[] BSD = {new Color(0xe8e4ff), new Color(0x3a1a7a)};

    private LicensesPage() {}

    private static Lib lib(String name, String url, String coordinate, String license, Color[] badge) {
        return new Lib(name, url, coordinate, license, badge[0], badge[1]);
    }

    /**
     * Every library the jar bundles (the runtime classpath, transitive ones included) and the Lucide
     * icons, in name order like oeHub's licenses page. Versions come from the build
     * ({@link BuildInfo}), so they are the ones actually shipped.
     */
    static final List<Lib> LIBS = List.of(
            lib("Apache Commons Lang", "https://commons.apache.org/proper/commons-lang/", "org.apache.commons:commons-lang3", "Apache 2.0", APACHE),
            lib("Ant-Style Path Matcher", "https://github.com/azagniotov/ant-style-path-matcher", "io.github.azagniotov:ant-style-path-matcher", "MIT", MIT),
            lib("Bouncy Castle", "https://www.bouncycastle.org", "org.bouncycastle:bcprov-jdk18on", "MIT", MIT),
            lib("Caffeine", "https://github.com/ben-manes/caffeine", "com.github.ben-manes.caffeine:caffeine", "Apache 2.0", APACHE),
            lib("Checker Framework Qualifiers", "https://checkerframework.org", "org.checkerframework:checker-qual", "MIT", MIT),
            lib("Error Prone Annotations", "https://errorprone.info", "com.google.errorprone:error_prone_annotations", "Apache 2.0", APACHE),
            lib("FlatLaf", "https://www.formdev.com/flatlaf/", "com.formdev:flatlaf", "Apache 2.0", APACHE),
            lib("gotpache-keytool", "https://github.com/tricatch/gotpache-keytool", "io.github.tricatch:gotpache-keytool", "MIT", MIT),
            lib("Guava", "https://github.com/google/guava", "com.google.guava:guava", "Apache 2.0", APACHE),
            lib("J2ObjC Annotations", "https://github.com/google/j2objc", "com.google.j2objc:j2objc-annotations", "Apache 2.0", APACHE),
            lib("Jackson", "https://github.com/FasterXML/jackson", "com.fasterxml.jackson.core:jackson-databind", "Apache 2.0", APACHE),
            lib("JSpecify", "https://jspecify.dev", "org.jspecify:jspecify", "Apache 2.0", APACHE),
            lib("JSVG", "https://github.com/weisJ/jsvg", "com.github.weisj:jsvg", "MIT", MIT),
            lib("LittleProxy", "https://github.com/LittleProxy/LittleProxy", "io.github.littleproxy:littleproxy", "Apache 2.0", APACHE),
            lib("Lucide", "https://lucide.dev", null, "ISC", MIT),
            lib("Netty", "https://netty.io", "io.netty:netty-common", "Apache 2.0", APACHE),
            lib("oe-proxy", "https://github.com/tricatch/oeProxy", "io.github.tricatch:oe-proxy", "MIT", MIT),
            lib("RSyntaxTextArea", "https://github.com/bobbylight/RSyntaxTextArea", "com.fifesoft:rsyntaxtextarea", "BSD 3-Clause", BSD),
            lib("SLF4J", "https://www.slf4j.org", "org.slf4j:slf4j-api", "MIT", MIT),
            lib("SnakeYAML", "https://bitbucket.org/snakeyaml/snakeyaml", "org.yaml:snakeyaml", "Apache 2.0", APACHE),
            lib("ZXing", "https://github.com/zxing/zxing", "com.google.zxing:core", "Apache 2.0", APACHE));

    /** Name (a link to the homepage) / version / license badge rows. */
    static JComponent build(AppContext ctx) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        // Baseline, so the version (a text field) lines up with the name and badge text.
        c.anchor = GridBagConstraints.BASELINE_LEADING;
        for (int i = 0; i < LIBS.size(); i++) {
            Lib l = LIBS.get(i);
            c.gridy = i;
            c.insets = new Insets(4, 0, 4, 24);
            c.gridx = 0;
            p.add(link(ctx, l.name(), l.url()), c);
            c.gridx = 1;
            String ver = l.coordinate() == null ? null : BuildInfo.dependency(l.coordinate());
            JTextField version = Ui.copyable(new JTextField(ver == null ? "-" : ver));
            version.setFont(Ui.mono(12f));
            version.setForeground(Ui.TEXT);
            p.add(version, c);
            c.gridx = 2;
            p.add(badge(l.license(), l.bg(), l.fg()), c);
        }
        c.gridx = 3;
        c.gridy = 0;
        c.weightx = 1;
        p.add(Box.createHorizontalGlue(), c);
        return page(p);
    }
}
