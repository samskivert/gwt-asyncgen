GWT AsyncGen is a simple Ant task with no dependencies that you can easily incorporate into your
project to take care of the annoyingly unautomated task of generating `FooServiceAsync` sources for
your `FooService` GWT `RemoteService` interface definitions.

We're aiming for maximum simplicity here. If you are not free of the burden of manually generating
`RemoteServiceAsync` classes on your project in five minutes, we will feel bad for having failed in
our goals.

# Get started
Here are the three steps you need to take to achieve GWT `RemoteService` asynchronous interface
generation enlightenment:

1. Download [gwt-asyncgen-1.0.jar](https://oss.sonatype.org/content/groups/public/com/samskivert/gwt-asyncgen/1.0/gwt-asyncgen-1.0.jar)
and put it in your project somewhere. Or add the `com.samskivert:gwt-asyncgen:1.0` dependency to
your Maven or Ivy project.

2. Add the following to your build.xml file:

```xml
<target name="genasync" depends="YOUR_COMPILE_TARGET">
  <taskdef name="genasync" classname="com.samskivert.asyncgen.AsyncGenTask"
           classpath="SOMEWHERE/gwt-asyncgen.jar"/>
  <genasync classpathref="YOUR_BUILD_CLASSPATH">
    <fileset dir="${src.dir}" includes="**/*Service.java"/>
  </genasync>
</target>
```

The all caps items will need to be configured by you and they should be self-explanatory. The thing
to note is that your `FooService` interfaces need to be compiled before you run this task because
it loads the classes via reflection to figure out what your service methods are.

3. Run the task with `ant genasync` or add it as a dependency to the build target that compiles
your GWT code. The task will automatically do nothing if your `FooServiceAsync.java` file is newer
than your `FooService.java` file, so it's fast and easy to just stick it in as a dependency all the
time so that you never even have to think about it.

# Why is it awesome?

Because it does some nice things:

  * You can customize the template used to generate source code (see below).
  * It automatically puts the right imports in, and handles generic types and the whole nine yards.
  * It grabs your parameter names from the `FooService` source and uses those in the generated source instead of `argN`.
  * It doesn't require Maven, or manually wiring some command line tool into an Ant task, or some IDE, or a barrel of monkeys.
  * If you're not using Ant, you can even run it from the command line (see below).

# Customize the template

If you don't like the precise format of the `FooServiceAsync` files that are generated, you can
provide a custom template file very easily. Copy
[gwt-asyncgen.tmpl](http://code.google.com/p/gwt-asyncgen/source/browse/trunk/lib/gwt-asyncgen.tmpl)
wherever you like, modify it to suite your tastes and then simply change your build file to:

```xml
<genasync classpathref="YOUR_BUILD_CLASSPATH" template="SOMEWHERE/my-awesome.tmpl">
```

And it will use your template. Naturally you will need to ensure that all of the parts of the
template are still in place or things won't work. A bare minimum template must contain at least the
following:

```java
package @PACKAGE@;

@IMPORTS@

public interface @SERVICE@Async
{
@METHOD_START@
    void @METHOD_NAME@ (@METHOD_ARGS@);
@METHOD_END@
}
```

Everything before `@METHOD_START@` is the prefix and is used as-is with the tokens replaced.

The text between `@METHOD_START@` and `@METHOD_END@` is repeated for each interface method (and a
blank line is placed between each repeat, sorry you can't change that).

Everything after `@METHOD_END@` is the postfix and is used as-is with the tokens replaced as well.
The current template doesn't have any tokens there, but if you wanted to use `@SERVICE@` down
there, you could. You could also use `@PACKAGE@` and `@IMPORTS@` though I'm not sure why you'd want
to do that.

# Command-line usage

If you aren't using Ant or anything that can run an Ant task, then you can run !AsyncGen from the
command line. It's just a little fiddlier. It will look something like the following:

```
java \
  -classpath gwt-asyncgen.jar:gwt-user.jar:extra_libs.jar:built_classes \
  com.samskivert.asyncgen.AsyncGenTool \
  -tmpl your_awesome.tmpl \ # this is optional
  src/java/com/whatever/FooService.java \
  src/java/com/whatever/BarService.java \
  ...
```

You need to provide in place of `built_classes` the directory where your compiled service files
exist. And `extra_libs.jar` is needed for any library code referenced from your service interfaces.
If you only reference your own code (or GWT code) from those interfaces, then you won't need any
extra jar files.

# Maven Usage

If you are a [Maven](http://maven.apache.org) user, you can still make use of the tool via the
magic of the Maven Antrun Plugin. Incorporate the following into your POM:

```xml
  <dependencies>
    <dependency>
      <groupId>com.samskivert</groupId>
      <artifactId>gwt-asyncgen</artifactId>
      <version>1.0</version>
      <scope>compile</scope>
      <optional>true</optional>
    </dependency>
  <dependencies>
  <build>
    <plugins>
      <plugin>
        <artifactId>maven-antrun-plugin</artifactId>
        <version>RELEASE</version>
        <executions>
          <execution>
            <phase>generate-sources</phase>
            <configuration>
              <tasks>
                <target name="genasync">
                  <taskdef name="genasync" classpath="maven.compile.classpath"
                           classname="com.samskivert.asyncgen.AsyncGenTask"/>
                  <genasync classpathref="maven.compile.classpath">
                    <fileset dir="${project.build.sourceDirectory}" includes="**/*Service.java"/>
                  </genasync>
                </target>
              </tasks>
            </configuration>
            <goals>
              <goal>run</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
```

Love that boilerplate! Note that the dependency has been marked "optional" because you don't want
to propagate it as a transitive dependency. It's something that your project needs only at compile
time. Maven has the "runtime" scope for things that are needed at runtime but not compile time, but
has no scope for things that are needed at compile time but not runtime.

# SBT Usage

If you use [SBT](http://github.com/harrah/xsbt), you can easily incorporate the tool into your
build using the command line interface. Add the following to your `project/plugins.sbt`:

```scala
libraryDependencies += "com.samskivert" % "gwt-asyncgen" % "1.0"
```

Then add the following to your build:

```scala
  val asyncGen = TaskKey[Unit]("async-gen", "Generates GWT service Async classes")
  private def asyncGenTask =
    (streams, sourceDirectory, classDirectory in Compile, dependencyClasspath in Compile) map {
      (s, sourceDir, classes, depCP) => {
        val cp = (classes +: depCP.map(_.data)) map(_.toURI.toURL)
        val loader = java.net.URLClassLoader.newInstance(cp.toArray)
        val genner = new com.samskivert.asyncgen.AsyncGenerator(loader, null) {
          override def fail (message :String, cause :Throwable) =
            new RuntimeException(message, cause)
        }
        val sources = (sourceDir ** "*Service.java").get
        s.log.debug("Generating async interfaces for: " + sources.mkString(", "))
        sources foreach { genner.processInterface(_) }
      }
    }

  // add to your project settings
  asyncGen <<= asyncGenTask
```
