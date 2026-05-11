plugins {
    alias(libs.plugins.android.library)
    id("maven-publish")
}

group = "com.kiber"
version = "0.2.5"

android {
    namespace = "com.kiber.sdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    publishing {
        singleVariant("release")
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.kiber"
                artifactId = "kiberwifi-sdk"
                version = project.version.toString()

                pom {
                    name.set("Kiber Wifi SDK")
                    description.set("Kiber WiFi/BLE foreground service manager SDK")
                }
            }
        }
        repositories {
            maven {
                name = "localTest"
                url = uri("${rootProject.layout.projectDirectory.asFile.absolutePath}/maven-repo")
            }
        }
    }
}
