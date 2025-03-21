plugins {
  id("com.android.application")
}

// This is always set to 'true' on Travis CI
val inCiBuild = System.getenv("CI") == "true"

android {
    compileSdk = Config.SdkVersions.compile
    namespace = "com.firebase.uidemo"

    defaultConfig {
        minSdk = Config.SdkVersions.min
        targetSdk = Config.SdkVersions.target

        versionName = Config.version
        versionCode = 1

        resourcePrefix("fui_")
        vectorDrawables.useSupportLibrary = true

        multiDexEnabled = true
    }

    buildTypes {
        named("debug").configure {
            // This empty config is only here to make Android Studio happy.
            // This build type is later ignored in the variantFilter section
        }

        named("release").configure {
            // For the purposes of the sample, allow testing of a proguarded release build
            // using the debug key
            signingConfig = signingConfigs["debug"]

            postprocessing {
                isRemoveUnusedCode = true
                isRemoveUnusedResources = true
                isObfuscate = true
            }
        }
    }

    compileOptions {    
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    lint {
        // Common lint options across all modules
        disable += mutableSetOf(
            "IconExpectedSize",
            "InvalidPackage", // Firestore uses GRPC which makes lint mad
            "NewerVersionAvailable", "GradleDependency", // For reproducible builds
            "SelectableText", "SyntheticAccessor", // We almost never care about this
            "MediaCapabilities"
        )

        checkAllWarnings = true
        warningsAsErrors = true
        abortOnError = true

        baseline = file("$rootDir/library/quality/lint-baseline.xml")
    }

    androidComponents {
        // Callback before variants are built.
        beforeVariants(selector().all()) { variant ->
            if (inCiBuild && variant.name == "debug") {
                variant.enable = false
            }
        }
    }
}

dependencies {
    implementation(project(":auth"))
    implementation(project(":firestore"))
    implementation(project(":database"))
    implementation(project(":storage"))

    implementation(Config.Libs.Androidx.lifecycleExtensions)
}

apply(plugin = "com.google.gms.google-services")
