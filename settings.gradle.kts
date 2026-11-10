rootProject.name = "oeHubApp"

// oe-proxy is developed in the sibling ../oeProxy checkout (same arrangement as oeHub). When it is
// present Gradle builds it and substitutes it for io.github.tricatch:oe-proxy; otherwise the
// published artifact is used.
if (file("../oeProxy").isDirectory) includeBuild("../oeProxy")
