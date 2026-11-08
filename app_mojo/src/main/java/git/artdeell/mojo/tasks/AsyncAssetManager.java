package git.artdeell.mojo.tasks;


import static git.artdeell.mojo.Architecture.archAsString;
import static git.artdeell.mojo.MojoApplication.sExecutorService;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.kdt.mcgui.ProgressLayout;

import git.artdeell.mojo.Tools;
import git.artdeell.mojo.multirt.MultiRTUtils;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class AsyncAssetManager {

    private AsyncAssetManager(){}

    /**
     * Attempt to install the java 8 runtime, if necessary
     * @param am App context
     */
    public static void unpackRuntime(AssetManager am) {
        /* Check if JRE is included */
        String rt_version = null;
        String current_rt_version = MultiRTUtils.readInternalRuntimeVersion("Internal");
        try {
            rt_version = Tools.read(am.open("components/jre/version"));
        } catch (IOException e) {
            Log.e("JREAuto", "JRE was not included on this APK.", e);
        }
        String exactJREName = MultiRTUtils.getExactJreName(8);
        if(current_rt_version == null && exactJREName != null && !exactJREName.equals("Internal")/*this clause is for when the internal runtime is goofed*/) return;
        if(rt_version == null) return;
        if(rt_version.equals(current_rt_version)) return;

        // Install the runtime in an async manner, hope for the best
        String finalRt_version = rt_version;
        sExecutorService.execute(() -> {

            try {
                MultiRTUtils.installRuntimeNamedBinpack(
                        am.open("components/jre/universal.tar.xz"),
                        am.open("components/jre/bin-" + archAsString(Tools.DEVICE_ARCHITECTURE) + ".tar.xz"),
                        "Internal", finalRt_version);
                MultiRTUtils.postPrepare("Internal");
            }catch (IOException e) {
                Log.e("JREAuto", "Internal JRE unpack failed", e);
            }
        });
    }

    /** Version of the Bedrock layout shipped in assets, written in its "bedrockLayoutVersion" field */
    private static final int BEDROCK_LAYOUT_VERSION = 4;

    /**
     * Moves an old shipped default layout out of the way (to default_old.json, never overwriting a backup) so the new Bedrock
     * layout gets copied. Recognized by content, so it also works after the editor re-saved it:
     * the original stock layout (Debug / Tab / 3rd buttons, no images) and the early Bedrock tests.
     * A layout that already has the current version, or that uses the user's own images, is kept.
     */
    private static void upgradeLegacyDefaultLayout() {
        File layout = new File(Tools.CTRLMAP_PATH, "default.json");
        if (!layout.isFile()) return;
        try {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            try (InputStream in = new FileInputStream(layout)) {
                byte[] chunk = new byte[8192];
                for (int read = in.read(chunk); read != -1; read = in.read(chunk)) buffer.write(chunk, 0, read);
            }
            byte[] data = buffer.toByteArray();
            boolean legacy;
            if (data.length > 1 && data[0] == 'P' && data[1] == 'K') {
                legacy = true; // zip layout from the very first Bedrock test
            } else {
                org.json.JSONObject json = new org.json.JSONObject(new String(data, StandardCharsets.UTF_8));
                if (json.optInt("bedrockLayoutVersion", 0) >= BEDROCK_LAYOUT_VERSION) return;
                legacy = looksLikeLegacyDefault(json);
            }
            if (!legacy) return;
            // Never overwrite an earlier backup: default_old.json, default_old_2.json, ...
            File backup = new File(layout.getParentFile(), "default_old.json");
            for (int n = 2; backup.exists() && n < 50; n++) {
                backup = new File(layout.getParentFile(), "default_old_" + n + ".json");
            }
            if (!layout.renameTo(backup) && !layout.delete()) {
                Log.w("AsyncAssetManager", "Could not move the old default layout");
            }
        } catch (Exception e) {
            Log.w("AsyncAssetManager", "Could not check the default layout", e);
        }
    }

    private static boolean looksLikeLegacyDefault(org.json.JSONObject json) {
        org.json.JSONArray controls = json.optJSONArray("mControlDataList");
        if (controls == null) return false;
        boolean bedrockTest = false, ownImages = false, debug = false, tab = false, third = false;
        for (int i = 0; i < controls.length(); i++) {
            org.json.JSONObject control = controls.optJSONObject(i);
            if (control == null) continue;
            String tag = control.optString("bitmapTag", "");
            if (tag.startsWith("res:bedrock_")) bedrockTest = true;
            else if (!tag.isEmpty()) ownImages = true;
            String name = control.optString("name", "");
            if (name.equals("Debug")) debug = true;
            if (name.equals("Tab")) tab = true;
            if (name.equals("3rd")) third = true;
        }
        if (bedrockTest) return true;                 // earlier Bedrock test layout
        return !ownImages && debug && tab && third;   // untouched old stock layout
    }

    /** Unpack single files, with no regard to version tracking */
    public static void unpackSingleFiles(Context ctx){
        ProgressLayout.setProgress(ProgressLayout.EXTRACT_SINGLE_FILES, 0);
        sExecutorService.execute(() -> {
            try {
                upgradeLegacyDefaultLayout();
                Tools.copyAssetFile(ctx, "default.json", Tools.CTRLMAP_PATH, false);
                // Bedrock-style layout, also shipped separately so existing installs can select it
                Tools.copyAssetFile(ctx, "Bedrock.json", Tools.CTRLMAP_PATH, true);
                Tools.copyAssetFile(ctx, "launcher_profiles.json", Tools.DIR_GAME_NEW, false);
                Tools.copyAssetFile(ctx,"resolv.conf",Tools.DIR_DATA, false);
            } catch (IOException e) {
                Log.e("AsyncAssetManager", "Failed to unpack critical components !");
            }
            ProgressLayout.clearProgress(ProgressLayout.EXTRACT_SINGLE_FILES);
        });
    }

    public static void unpackComponents(Context ctx){
        ProgressLayout.setProgress(ProgressLayout.EXTRACT_COMPONENTS, 0);
        sExecutorService.execute(() -> {
            tryUnpackComponent(ctx, "caciocavallo", false);
            tryUnpackComponent(ctx, "caciocavallo17", false);

            tryUnpackComponent(ctx, "security", true);
            tryUnpackComponent(ctx, "forge_installer", true);
            tryUnpackComponent(ctx, "authlib-injector", true);
            tryUnpackComponent(ctx, "noblocklist", true);
            ProgressLayout.clearProgress(ProgressLayout.EXTRACT_COMPONENTS);
        });
    }

    private static String readInstalledComponentVersion(File componentRoot) {
        File localVersionFile = new File(componentRoot, "version");
        try(FileInputStream fileInputStream = new FileInputStream(localVersionFile)) {
            return IOUtils.toString(fileInputStream, StandardCharsets.UTF_8);
        }catch (IOException ignored) {}
        return null;
    }

    private static String readBuiltinComponentVersion(AssetManager assetManager, String componentName) {
        String componentVersionLocation = "components/"+componentName+"/version";
        try (InputStream inputStream = assetManager.open(componentVersionLocation)) {
            return IOUtils.toString(inputStream, StandardCharsets.UTF_8);
        }catch (IOException ignored) {}
        return null;
    }

    private static void tryUnpackComponent(Context ctx, String component, boolean privateDirectory) {
        try {
            unpackComponent(ctx, component, privateDirectory);
        }catch (IOException e) {
            Log.e("AssetUnpacker", "Failed to unpack component "+component, e);
        }
    }

    private static void unpackComponent(Context ctx, String component, boolean privateDirectory) throws IOException {
        AssetManager am = ctx.getAssets();
        String rootDir = privateDirectory ? Tools.DIR_DATA : Tools.DIR_GAME_HOME;
        File componentTarget = new File(rootDir, component);
        String installedVersion = readInstalledComponentVersion(componentTarget);
        String builtinVersion = readBuiltinComponentVersion(am, component);
        if(installedVersion != null && installedVersion.equals(builtinVersion)) {
            Log.i("AssetUnpacker", "Component "+component+" is up-to-date, continuing...");
            return;
        }
        Log.i("AssetUnpacker", "Updating "+component);

        if(componentTarget.exists()) {
            FileUtils.deleteDirectory(componentTarget);
        }
        if(!componentTarget.mkdirs()) {
            throw new IOException("Failed to create directory for "+component);
        }

        String componentSource = "components/" + component;

        String[] fileList = am.list(componentSource);
        for (String fileName : fileList) {
            if(fileName.equals("version")) continue;
            String sourcePath = componentSource + "/" + fileName;
            Tools.copyAssetFile(ctx, sourcePath, componentTarget.getAbsolutePath(), true);
        }

        // Always write the version file separately after extracting everything else, to improve
        // reliability.
        Tools.write(new File(componentTarget, "version"), builtinVersion);
    }

    public static void extractDefaultSettings(Context context, File gamedir)  {
        try {
            String gameDirPath = gamedir.getAbsolutePath();
            Tools.copyAssetFile(context, "options.txt", gameDirPath, false);
        }catch (IOException e) {
            Tools.showError(context, e);
        }
    }
}
