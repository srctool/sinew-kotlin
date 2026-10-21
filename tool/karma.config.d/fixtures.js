// fixture(): serves the test resources the Kotlin plugin copies next to the test's .wasm, at /base/kotlin/<name>.
config.files.push({ pattern: config.basePath + "/kotlin/**/*", included: false, served: true, watched: false, nocache: true });
