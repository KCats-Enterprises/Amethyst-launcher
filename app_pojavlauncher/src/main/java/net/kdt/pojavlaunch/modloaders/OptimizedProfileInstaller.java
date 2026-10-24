package net.kdt.pojavlaunch.modloaders;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.JMinecraftVersionList;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModDownloader;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.DownloaderProgressWrapper;
import net.kdt.pojavlaunch.utils.DownloadUtils;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.utils.GsonJsonUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/// Sets up a Fabric profile with quality of life and performance mods, on the newest release
/// all the core mods support, then selects it. Runs once, after the launcher gets the version list.
///
/// Sodium and other renderer replacements are left out, Amethyst does not support them.
public class OptimizedProfileInstaller {
    private static final String TAG = "OptimizedProfile";
    private static final String PREF_KEY_INSTALLED = "optimizedProfileInstalled";
    private static final String INSTANCE_PATH = "custom_instances/optimized";
    private static final String MODRINTH_VERSIONS_URL = "https://api.modrinth.com/v2/project/%s/version?loaders=%s&game_versions=%s";
    // Every one of these needs a build for the picked Minecraft version
    private static final String[] CORE_MODS = {"fabric-api", "appleskin", "shulkerboxtooltip", "lithium", "ferrite-core"};
    // Added when they have a build for the picked Minecraft version
    private static final String[] EXTRA_MODS = {"modernfix", "entityculling", "modmenu"};
    // Shipped in earlier builds but broke the game (Dynamic FPS gave a black screen), deleted on launch
    private static final String[] REMOVED_MODS = {"dynamic-fps", "dynamic_fps", "dynamicfps"};
    // How many of the newest releases to try before giving up
    private static final int MAX_CANDIDATES = 10;
    // The launcher activity can get recreated while we are still installing
    private static final AtomicBoolean sInstalling = new AtomicBoolean(false);

    public static void installIfNeeded(Context context, @Nullable JMinecraftVersionList versionList) {
        removeBrokenMods();
        if (versionList == null || versionList.versions == null) return;
        if (LauncherPreferences.DEFAULT_PREF.getBoolean(PREF_KEY_INSTALLED, false)) return;
        if (!Tools.isOnline(context)) return;
        if (!sInstalling.compareAndSet(false, true)) return;

        ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.global_waiting);
        try {
            if (!install(context, versionList)) Log.w(TAG, "No release has builds of all the core mods");
        } catch (Exception e) {
            Log.e(TAG, "Failed to set up the optimized profile", e);
            Tools.showErrorRemote(context, R.string.modpack_install_download_failed, e);
        } finally {
            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
            sInstalling.set(false);
        }
    }

    private static void removeBrokenMods() {
        File[] mods = new File(Tools.DIR_GAME_HOME, INSTANCE_PATH + "/mods").listFiles();
        if (mods == null) return;
        for (File mod : mods) {
            String name = mod.getName().toLowerCase();
            for (String removedMod : REMOVED_MODS) {
                if (!name.contains(removedMod)) continue;
                Log.i(TAG, "Removing " + mod.getName());
                //noinspection ResultOfMethodCallIgnored
                mod.delete();
                break;
            }
        }
    }

    private static boolean install(Context context, JMinecraftVersionList versionList) throws IOException {
        FabricVersion[] fabricGameVersions = FabriclikeUtils.FABRIC_UTILS.downloadGameVersions();
        if (fabricGameVersions == null) throw new IOException("Failed to get the Fabric game versions");
        Set<String> fabricSupported = new HashSet<>();
        for (FabricVersion version : fabricGameVersions) fabricSupported.add(version.version);

        // The manifest is sorted from newest to oldest
        int candidates = 0;
        for (JMinecraftVersionList.Version version : versionList.versions) {
            if (!"release".equals(version.type) || !fabricSupported.contains(version.id)) continue;
            if (candidates++ >= MAX_CANDIDATES) break;
            Map<String, JsonObject> mods = resolveMods(version.id);
            if (mods == null) continue;
            createProfile(context, version.id, mods);
            return true;
        }
        return false;
    }

    /// @return The mod versions to install, keyed by project id, or null if a core mod is missing
    @Nullable
    private static Map<String, JsonObject> resolveMods(String gameVersion) throws IOException {
        Map<String, JsonObject> mods = new LinkedHashMap<>();
        for (String slug : CORE_MODS) {
            JsonObject modVersion = findModVersion(slug, gameVersion);
            if (modVersion == null) return null;
            mods.put(modVersion.get("project_id").getAsString(), modVersion);
        }
        for (String slug : EXTRA_MODS) {
            JsonObject modVersion = findModVersion(slug, gameVersion);
            if (modVersion != null) mods.put(modVersion.get("project_id").getAsString(), modVersion);
        }

        // Pull in required dependencies, like Cloth Config for ShulkerBoxTooltip
        ArrayDeque<JsonObject> toCheck = new ArrayDeque<>(mods.values());
        while (!toCheck.isEmpty()) {
            JsonArray dependencies = toCheck.pop().getAsJsonArray("dependencies");
            if (dependencies == null) continue;
            for (JsonElement element : dependencies) {
                JsonObject dependency = element.getAsJsonObject();
                String projectId = GsonJsonUtils.getStringSafe(dependency, "project_id");
                if (!"required".equals(GsonJsonUtils.getStringSafe(dependency, "dependency_type"))) continue;
                if (projectId == null || mods.containsKey(projectId)) continue;
                JsonObject modVersion = findModVersion(projectId, gameVersion);
                if (modVersion == null) return null;
                mods.put(projectId, modVersion);
                toCheck.push(modVersion);
            }
        }
        return mods;
    }

    /// @return The newest Fabric build of a mod for this game version, preferring stable ones
    @Nullable
    private static JsonObject findModVersion(String project, String gameVersion) throws IOException {
        String url = String.format(MODRINTH_VERSIONS_URL, project,
                URLEncoder.encode("[\"fabric\"]", "UTF-8"),
                URLEncoder.encode("[\"" + gameVersion + "\"]", "UTF-8"));
        JsonArray versions = JsonParser.parseString(DownloadUtils.downloadString(url)).getAsJsonArray();
        if (versions.size() == 0) return null;
        for (JsonElement element : versions) {
            JsonObject version = element.getAsJsonObject();
            if ("release".equals(GsonJsonUtils.getStringSafe(version, "version_type"))) return version;
        }
        return versions.get(0).getAsJsonObject();
    }

    private static void createProfile(Context context, String gameVersion, Map<String, JsonObject> mods) throws IOException {
        String loaderVersion = null;
        FabricVersion[] loaderVersions = FabriclikeUtils.FABRIC_UTILS.downloadLoaderVersions(gameVersion);
        if (loaderVersions != null) {
            for (FabricVersion version : loaderVersions) {
                if (version.stable) {
                    loaderVersion = version.version;
                    break;
                }
            }
        }
        if (loaderVersion == null) throw new IOException("No stable Fabric loader for " + gameVersion);

        String fabricJson = DownloadUtils.downloadString(FabriclikeUtils.FABRIC_UTILS.createJsonDownloadUrl(gameVersion, loaderVersion));
        String versionId = JsonParser.parseString(fabricJson).getAsJsonObject().get("id").getAsString();
        File versionJsonDir = new File(Tools.DIR_HOME_VERSION, versionId);
        FileUtils.ensureDirectory(versionJsonDir);
        Tools.write(new File(versionJsonDir, versionId + ".json").getAbsolutePath(), fabricJson);

        File instanceDir = new File(Tools.DIR_GAME_HOME, INSTANCE_PATH);
        // Leftovers from an earlier attempt may be for another game version
        File[] oldMods = new File(instanceDir, "mods").listFiles();
        if (oldMods != null) {
            for (File oldMod : oldMods) //noinspection ResultOfMethodCallIgnored
                oldMod.delete();
        }
        ModDownloader modDownloader = new ModDownloader(instanceDir);
        for (JsonObject modVersion : mods.values()) {
            JsonObject file = getPrimaryFile(modVersion);
            JsonObject hashes = file.getAsJsonObject("hashes");
            modDownloader.submitDownload(file.get("size").getAsInt(),
                    "mods/" + file.get("filename").getAsString(),
                    hashes == null ? null : GsonJsonUtils.getStringSafe(hashes, "sha1"),
                    file.get("url").getAsString());
        }
        modDownloader.awaitFinish(new DownloaderProgressWrapper(R.string.modpack_download_downloading_mods, ProgressLayout.INSTALL_MODPACK));
        Tools.copyAssetFile(context, "options.txt", instanceDir.getAbsolutePath(), false);

        LauncherProfiles.load();
        MinecraftProfile profile = new MinecraftProfile();
        profile.name = "Optimized " + gameVersion;
        profile.lastVersionId = versionId;
        profile.gameDir = Tools.LAUNCHERPROFILES_RTPREFIX + INSTANCE_PATH;
        profile.icon = FabriclikeUtils.FABRIC_UTILS.getIconName();
        String profileKey = LauncherProfiles.getFreeProfileKey();
        LauncherProfiles.mainProfileJson.profiles.put(profileKey, profile);
        LauncherProfiles.write();

        LauncherPreferences.DEFAULT_PREF.edit()
                .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, profileKey)
                .putBoolean(PREF_KEY_INSTALLED, true)
                .apply();
        ExtraCore.setValue(ExtraConstants.OPTIMIZED_PROFILE_READY, profileKey);
    }

    private static JsonObject getPrimaryFile(JsonObject modVersion) {
        JsonArray files = modVersion.getAsJsonArray("files");
        for (JsonElement element : files) {
            JsonObject file = element.getAsJsonObject();
            if (file.has("primary") && file.get("primary").getAsBoolean()) return file;
        }
        return files.get(0).getAsJsonObject();
    }
}
