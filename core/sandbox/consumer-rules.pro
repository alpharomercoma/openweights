# Everything the interpreter's native half reaches by name.
#
# Same reasoning as core/engine's rules, and the same failure mode: JNI does not link, it
# looks names up as strings at runtime, so a rename becomes an UnsatisfiedLinkError the
# first time a script runs. Debug builds never show it, because R8 does not run there.

# nativeRun resolves as Java_io_github_..._QuickJs_nativeRun, so the class name and the
# method name both have to survive.
-keep class io.github.alpharomercoma.openweights.core.sandbox.QuickJs {
    native <methods>;
}

# The service is named in the manifest and instantiated by the system, never from Kotlin
# that R8 can see. It runs in an isolated process, so this failing means scripts stop
# working rather than anything unsafe, but it fails silently and that is worse.
-keep class io.github.alpharomercoma.openweights.core.sandbox.ScriptService { *; }
