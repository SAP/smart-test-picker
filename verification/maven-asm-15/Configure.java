import java.nio.file.*;

/** Changes only the disposable ASM-10 measurement overlay, never the subject POM. */
public final class Configure {
    public static void main(String[] args) throws Exception {
        Path path = Path.of(args[0]);
        String xml = Files.readString(path);
        String original = "<runOrder>alphabetical</runOrder>";
        if (!xml.contains(original)) throw new IllegalStateException("Missing known measurement runOrder");
        Files.writeString(path, xml.replace(original, "<runOrder>${stp.measurement.runOrder}</runOrder>"));
    }
}
