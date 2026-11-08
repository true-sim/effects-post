plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
    id("truesim.slang")
}

description = "Post-processing for true-sim: bloom and the other post effects as technique slots of a view, in the " +
    "engine's frame graph, over its generic hooks."

dependencies {
    api("com.truesim.engine:engine-render:0.193.0") // the frame graph, the renderer's keys and Slang modules
    api("com.truesim.engine:engine-scene:0.193.0") // the scene's resources: textures and tables loaded as assets
    testImplementation(testFixtures("com.truesim.engine:engine-render:0.193.0")) // the headless renderer
}

// The push layouts of src/main/slang (post/*.slang), as Java.
slang {
    javaPackage = "com.truesim.effects.post.materials" // the test post materials' parameter records
    pushPackage = "com.truesim.effects.post.layout"
    pushBase = "com.truesim.engine.vulkan.PushConstants" // push layouts are writers too
}

// The benchmarks (TRUESIM_BENCHMARK=true) measure the code, not its coverage probes.
tasks.test {
    if (providers.environmentVariable("TRUESIM_BENCHMARK").orNull == "true"
        || providers.environmentVariable("TRUESIM_SCREENSHOTS").orNull == "true") {
        maxHeapSize = "4g"
        extensions.configure<JacocoTaskExtension> { isEnabled = false }
    }
}
