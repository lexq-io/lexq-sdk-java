import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Checks that the copies in spec/ match the SHA-256 values in spec/spec.lock, and that
 * CONTRACT.md names the version spec.lock records. Run it from the repository root, or
 * pass the root as the only argument:
 *
 * <pre>java scripts/CheckSpec.java [repository root]</pre>
 *
 * <p>It fails when spec/ does not hold exactly SPEC.md, values.json, and spec.lock, each a
 * regular file; when spec.lock does not record exactly one version and one SHA-256 for
 * each copy; when a copy's SHA-256 differs from the one spec.lock records; or when
 * CONTRACT.md does not contain exactly one string of the form v1.2.3, or that string is
 * not the version spec.lock records.
 */
public final class CheckSpec {
    private static final List<String> COPIES = List.of("SPEC.md", "values.json");
    private static final String LOCK = "spec.lock";

    // A version as CONTRACT.md writes it, such as v1.2.3. It matches a version that ends a
    // sentence, and it does not match v1.2.3-rc1, v1.2.3.4, or v01.2.3.
    private static final Pattern VERSION = Pattern.compile(
            "(?<![\\w.-])v(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)(?![\\w-]|\\.\\d)");
    private static final Pattern LOCK_VERSION = Pattern.compile("\"version\"\\s*:\\s*\"([^\"\\\\]*)\"");

    public static void main(String[] args) throws IOException, NoSuchAlgorithmException {
        if (args.length > 1) {
            fail(List.of("Usage: java scripts/CheckSpec.java [repository root]"));
        }
        Path root = Path.of(args.length == 1 ? args[0] : ".");
        Path spec = root.resolve("spec");
        List<String> failures = new ArrayList<>();

        if (!Files.isDirectory(spec, LinkOption.NOFOLLOW_LINKS)) {
            fail(List.of("spec/ is missing, or it is not a directory."));
        }
        Set<String> expected = new TreeSet<>(COPIES);
        expected.add(LOCK);
        Set<String> names = new TreeSet<>();
        try (Stream<Path> entries = Files.list(spec)) {
            entries.forEach(entry -> names.add(entry.getFileName().toString()));
        }
        if (!names.equals(expected)) {
            failures.add("spec/ must hold exactly " + expected + ", but it holds " + names + ".");
        }
        for (String name : names) {
            if (expected.contains(name) && !Files.isRegularFile(spec.resolve(name), LinkOption.NOFOLLOW_LINKS)) {
                failures.add("spec/" + name + " is not a regular file.");
            }
        }
        if (!failures.isEmpty()) {
            fail(failures);
        }

        String lock = read(spec.resolve(LOCK));
        String lockVersion = only(LOCK_VERSION, lock, "\"version\" in spec/spec.lock", failures);
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        for (String name : COPIES) {
            Pattern recorded = Pattern.compile("\"" + Pattern.quote(name)
                    + "\"\\s*:\\s*\\{\\s*\"sha256\"\\s*:\\s*\"([0-9a-f]{64})\"\\s*}");
            String want = only(recorded, lock, "The SHA-256 of " + name + " in spec/spec.lock", failures);
            String have = HexFormat.of().formatHex(sha256.digest(Files.readAllBytes(spec.resolve(name))));
            if (want != null && !want.equals(have)) {
                failures.add("spec/" + name + " has SHA-256 " + have + ", but spec/spec.lock records " + want + ".");
            }
        }

        String named = only(VERSION, read(root.resolve("CONTRACT.md")), "A version in CONTRACT.md", failures);
        if (named != null && lockVersion != null && !named.equals(lockVersion)) {
            failures.add("CONTRACT.md names " + named + ", but spec/spec.lock records " + lockVersion + ".");
        }

        if (!failures.isEmpty()) {
            fail(failures);
        }
        System.out.println("spec/ matches spec/spec.lock for " + named + ", the version CONTRACT.md names.");
    }

    /**
     * The one match of the pattern in the text: its first group, or the whole match. When the
     * pattern does not match exactly once, it adds a failure and returns null.
     */
    private static String only(Pattern pattern, String text, String what, List<String> failures) {
        Matcher matcher = pattern.matcher(text);
        List<String> found = new ArrayList<>();
        while (matcher.find()) {
            found.add(matcher.groupCount() > 0 ? matcher.group(1) : matcher.group());
        }
        if (found.size() != 1) {
            failures.add(what + " must appear exactly once, but " + found.size() + " were found"
                    + (found.isEmpty() ? "." : ": " + found + "."));
            return null;
        }
        return found.get(0);
    }

    private static String read(Path file) throws IOException {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            fail(List.of(file + " is missing."));
            return null;
        }
    }

    private static void fail(List<String> failures) {
        for (String failure : failures) {
            System.err.println(failure);
        }
        System.exit(1);
    }
}
