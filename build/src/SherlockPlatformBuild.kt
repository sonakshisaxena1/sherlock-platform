// TODO: Copyright
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.runBlocking
import org.jetbrains.intellij.build.*
import org.jetbrains.intellij.build.impl.BuildContextImpl


@Suppress("RAW_RUN_BLOCKING", "UNUSED")
object SherlockPlatformBuild {
  @JvmStatic
  fun main() {
    runBlocking {
      val home = IdeaProjectLoaderUtil.guessCommunityHome(javaClass)
      val properties = SherlockPlatformProperties()
      val buildContext = BuildContextImpl.createContext(home.communityRoot, properties)
      val tasks = createBuildTasks(buildContext)
      tasks.buildDistributions()
    }
  }
}

// TODO: Does BaseIdeaProperties add some stuff we don't need?
private class SherlockPlatformProperties : BaseIdeaProperties() {
  init {
    platformPrefix = "SherlockPlatform"
    applicationInfoModule = "intellij.idea.community.customization" // TODO: better to use own module.
    useSplash = false
    productLayout.buildAllCompatiblePlugins = false
    productLayout.prepareCustomPluginRepositoryForPublishedPlugins = false
    productLayout.productImplementationModules = listOf(
      "intellij.platform.starter",
      "intellij.idea.community.customization",
    )
    productLayout.bundledPluginModules = mutableListOf()
    productLayout.pluginLayouts = persistentListOf()
  }

  override val baseFileName: String = "sherlock-platform"

  override fun getBaseArtifactName(appInfo: ApplicationInfoProperties, buildNumber: String): String = "sherlock-platform-$buildNumber"

  override fun getSystemSelector(appInfo: ApplicationInfoProperties, buildNumber: String): String = "SherlockPlatform"

  override fun createLinuxCustomizer(projectHome: String): LinuxDistributionCustomizer {
    return object : LinuxDistributionCustomizer() {}
  }

  override fun createMacCustomizer(projectHome: String): MacDistributionCustomizer {
    return object : MacDistributionCustomizer() {
      init {
        bundleIdentifier = "com.google.sherlock.platform"
        icnsPath = "${projectHome}/build/conf/ideaCE/mac/images/idea.icns" // TODO
      }
    }
  }

  override fun createWindowsCustomizer(projectHome: String): WindowsDistributionCustomizer {
    return object : WindowsDistributionCustomizer() {
      init {
        icoPath = "${projectHome}/build/conf/ideaCE/win/images/idea_CE.ico" // TODO
      }
    }
  }
}
