//
// Pacman - the Scaled package manager
// https://github.com/scaled/pacman/blob/master/LICENSE

package scaled.pacman;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Handles the compilation of a package's code.
 */
public class PackageBuilder {

  public PackageBuilder (PackageRepo repo, Package pkg) {
    _repo = repo;
    _pkg = pkg;
  }

  /** Cleans out the build results directory for all modules in this package. */
  public void clean () throws IOException {
    for (Module mod : _pkg.modules()) {
      Filez.deleteAll(mod.classesDir());
    }
  }

  /** Cleans and builds all modules in this package. */
  public void build () throws IOException {
    for (Module mod : _pkg.modules()) build(mod);
  }

  /** Cleans and builds any modules in this package which have source files that have been modified
    * since the previous build. */
  public boolean rebuild () throws IOException {
    boolean rebuilt = false;
    for (Module mod : _pkg.modules()) rebuilt = rebuild(mod) || rebuilt;
    return rebuilt;
  }

  protected void build (Module mod) throws IOException {
    String what = mod.pkg.name;
    if (!mod.isDefault()) what += "#" + mod.name;
    _repo.log.log("Building " + what + "...");

    // clear out and (re)create (if needed), the build output directory
    Filez.deleteAll(mod.classesDir());
    Files.createDirectories(mod.classesDir());

    // if a resources directory exists, copy that over
    Path rsrcDir = mod.resourcesDir();
    if (Files.exists(rsrcDir)) Filez.copyAll(rsrcDir, mod.classesDir());

    // now build whatever source we find in the project
    Map<String,Path> srcDirs = mod.sourceDirs();

    // if we have scala sources, use scalac to build scala+java code
    Path scalaDir = srcDirs.get("scala"), javaDir = srcDirs.get("java");
    // compile scala first in case there are java files that depend on scala's; scalac does some
    // fiddling to support mixed compilation but it doesn't generate bytecode for .javas
    if (scalaDir != null) buildScala(mod, scalaDir, javaDir);
    if (javaDir != null) buildJava(mod, javaDir);
    // TODO: support other languages

    // finally jar everything up
    createJar(mod.classesDir(), mod.moduleJar());
  }

  protected boolean rebuild (Module mod) throws IOException {
    Path moduleJar = mod.moduleJar();
    long lastBuild = Files.exists(moduleJar) ? Files.getLastModifiedTime(moduleJar).toMillis() : 0L;
    if (!Filez.existsNewer(lastBuild, mod.mainDir())) return false;
    build(mod);
    return true;
  }

  protected void buildScala (Module mod, Path scalaDir, Path javaDir) throws IOException {
    List<String> cmd = new ArrayList<>();
    cmd.add(findJavaHome().resolve("bin").resolve("java").toString());

    String scalacId = "org.scala-lang:scala-compiler:2.11.0";
    cmd.add("-cp");
    cmd.add(classpathToString(_repo.mvn.resolve(Arrays.asList(RepoId.parse(scalacId)))));
    cmd.add("scala.tools.nsc.Main");

    cmd.add("-d"); cmd.add(mod.root.relativize(mod.classesDir()).toString());
    cmd.addAll(mod.pkg.scopts);
    List<Path> cp = buildClasspath(mod);
    if (!cp.isEmpty()) { cmd.add("-classpath"); cmd.add(classpathToString(cp)); }
    if (javaDir != null) addSources(mod.root, javaDir, ".java", cmd);
    addSources(mod.root, scalaDir, ".scala", cmd);

    Exec.exec(mod.root, cmd).expect(0, "Scala build failed.");
  }

  protected void buildJava (Module mod, Path javaDir) throws IOException {
    List<String> cmd = new ArrayList<>();
    cmd.add(findJavaHome().resolve("bin").resolve("javac").toString());

    cmd.addAll(mod.pkg.jcopts);
    cmd.add("-d"); cmd.add(mod.root.relativize(mod.classesDir()).toString());
    List<Path> cp = buildClasspath(mod);
    if (!cp.isEmpty()) { cmd.add("-cp"); cmd.add(classpathToString(cp)); }
    addSources(mod.root, javaDir, ".java", cmd);

    Exec.exec(mod.root, cmd).expect(0, "Java build failed.");
  }

  protected void createJar (Path sourceDir, Path targetJar) throws IOException {
    // if the old jar file exists, move it out of the way; this reduces the likelihood that we'll
    // cause a JVM to crash by truncating and replacing a jar file out from under it
    if (Files.exists(targetJar)) {
      Path oldJar = targetJar.resolveSibling("old-"+targetJar.getFileName());
      Files.move(targetJar, oldJar, StandardCopyOption.REPLACE_EXISTING);
    }
    List<String> cmd = new ArrayList<>();
    cmd.add("jar");
    cmd.add("-cf");
    cmd.add(targetJar.toString());
    cmd.add(".");
    Exec.exec(sourceDir, cmd).expect(0, "Jar creation failed.");
  }

  protected void addSources (Path root, Path dir, String suff, List<String> into) throws IOException {
    Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
      @Override public FileVisitResult visitFile (Path file, BasicFileAttributes attrs)
      throws IOException {
        // TODO: allow symlinks to source files? that seems wacky...
        if (attrs.isRegularFile() && file.getFileName().toString().endsWith(suff)) {
          into.add(root.relativize(file).toString());
        }
        return FileVisitResult.CONTINUE;
      }
    });
  }

  protected List<Path> buildClasspath (Module mod) {
    List<Path> cp = mod.depends(_repo.resolver, false).classpath();
    cp.remove(mod.classesDir());
    return cp;
  }

  protected String classpathToString (List<Path> paths) {
    String pathSep = System.getProperty("path.separator");
    StringBuilder sb = new StringBuilder();
    for (Path path : paths) {
      if (sb.length() > 0) sb.append(pathSep);
      sb.append(path);
    }
    return sb.toString();
  }

  protected Path findJavaHome () throws IOException {
    Path jreHome = Paths.get(System.getProperty("java.home"));
    Path javaHome = jreHome.getParent();
    if (isJavaHome(javaHome)) return javaHome;
    if (isJavaHome(jreHome)) return jreHome;
    throw new IllegalStateException("Unable to find java in " + jreHome + " or " + javaHome);
  }

  protected boolean isJavaHome (Path javaHome) {
    return Files.exists(javaHome.resolve("bin").resolve("java"));
  }

  protected final PackageRepo _repo;
  protected final Package _pkg;
}
