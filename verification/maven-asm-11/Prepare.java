import java.nio.file.*;

/** Adds only a disposable observation agent to the existing ASM-10 measurement POM. */
public final class Prepare {
    public static void main(String[] args) throws Exception {
        Path pom=Path.of(args[0]);String text=Files.readString(pom);
        String original="<argLine>@{argLine} -Xmx768m</argLine>";
        if(!text.contains(original))throw new IllegalStateException("ASM-10 argLine not found");
        Files.writeString(pom,text.replace(original,"<argLine>@{argLine} -Xmx768m -javaagent:"+args[1]+"="+args[2]+"</argLine>"));
    }
}
