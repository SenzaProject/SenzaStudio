package javax.lang.model;

/**
 * Pengganti minimal untuk javax.lang.model.SourceVersion yang tidak ada di Android.
 * Dibutuhkan oleh compiler ECJ (Eclipse) yang berjalan di dalam Senza Studio.
 */
public enum SourceVersion {
    RELEASE_0, RELEASE_1, RELEASE_2, RELEASE_3, RELEASE_4, RELEASE_5, RELEASE_6, RELEASE_7,
    RELEASE_8, RELEASE_9, RELEASE_10, RELEASE_11, RELEASE_12, RELEASE_13, RELEASE_14,
    RELEASE_15, RELEASE_16, RELEASE_17;

    public static SourceVersion latest() {
        return RELEASE_8;
    }

    public static SourceVersion latestSupported() {
        return RELEASE_8;
    }

    public static boolean isIdentifier(CharSequence name) {
        String id = name.toString();
        if (id.length() == 0) return false;
        int cp = id.codePointAt(0);
        if (!Character.isJavaIdentifierStart(cp)) return false;
        for (int i = Character.charCount(cp); i < id.length(); i += Character.charCount(cp)) {
            cp = id.codePointAt(i);
            if (!Character.isJavaIdentifierPart(cp)) return false;
        }
        return true;
    }

    public static boolean isName(CharSequence name) {
        return isName(name, latest());
    }

    public static boolean isName(CharSequence name, SourceVersion version) {
        String id = name.toString();
        for (String s : id.split("\\.", -1)) {
            if (!isIdentifier(s) || isKeyword(s, version)) return false;
        }
        return true;
    }

    public static boolean isKeyword(CharSequence s) {
        return isKeyword(s, latest());
    }

    public static boolean isKeyword(CharSequence s, SourceVersion version) {
        String k = s.toString();
        switch (k) {
            case "abstract": case "continue": case "for": case "new": case "switch":
            case "assert": case "default": case "if": case "package": case "synchronized":
            case "boolean": case "do": case "goto": case "private": case "this":
            case "break": case "double": case "implements": case "protected": case "throw":
            case "byte": case "else": case "import": case "public": case "throws":
            case "case": case "enum": case "instanceof": case "return": case "transient":
            case "catch": case "extends": case "int": case "short": case "try":
            case "char": case "final": case "interface": case "static": case "void":
            case "class": case "finally": case "long": case "strictfp": case "volatile":
            case "const": case "float": case "native": case "super": case "while":
            case "true": case "false": case "null":
                return true;
            default:
                return false;
        }
    }
}
