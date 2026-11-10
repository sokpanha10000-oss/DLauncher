package git.artdeell.mojo.input.buttons;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Reads the flight abilities of the player from the world that was opened last.
 * This is how the launcher knows, without any help from the game, whether the player is in a
 * mode that can fly (creative / spectator) and whether he was flying when the world was saved.
 */
final class WorldAbilities {
    static final class Snapshot {
        /** The player may fly (creative, spectator, or granted by a command) */
        final boolean mayFly;
        /** The player was flying when the world was last saved */
        final boolean flying;
        /** When the world was opened last (session.lock), milliseconds since the epoch */
        final long openedAt;
        /** When the file that the abilities came from was written */
        final long savedAt;

        Snapshot(boolean mayFly, boolean flying, long openedAt, long savedAt) {
            this.mayFly = mayFly;
            this.flying = flying;
            this.openedAt = openedAt;
            this.savedAt = savedAt;
        }
    }

    private WorldAbilities() {}

    /** @return the abilities from the most recently opened world in savesDir, or null if unknown */
    static Snapshot readLatest(File savesDir) {
        if(savesDir == null) return null;
        File[] worlds = savesDir.listFiles(File::isDirectory);
        if(worlds == null) return null;

        File newest = null;
        long newestOpened = Long.MIN_VALUE;
        for(File world : worlds) {
            File levelDat = new File(world, "level.dat");
            if(!levelDat.isFile()) continue;
            File lock = new File(world, "session.lock");
            long opened = lock.isFile() ? lock.lastModified() : levelDat.lastModified();
            if(opened > newestOpened) {
                newestOpened = opened;
                newest = world;
            }
        }
        if(newest == null) return null;

        try {
            return read(newest, newestOpened);
        }catch (Exception e) {
            return null; // The save can be half written while the game is saving, try again later
        }
    }

    @SuppressWarnings("unchecked")
    private static Snapshot read(File world, long openedAt) throws IOException {
        File levelDat = new File(world, "level.dat");
        Map<String, Object> root = readNbt(levelDat);
        Map<String, Object> data = compound(root, "Data");
        if(data == null) return null;

        // 1. The player stored inside level.dat (singleplayer, most versions)
        Map<String, Object> abilities = compound(compound(data, "Player"), "abilities");
        long savedAt = levelDat.lastModified();

        // 2. Newer versions keep the player in playerdata/<uuid>.dat
        if(abilities == null) {
            File playerData = newestFile(new File(world, "playerdata"), ".dat");
            if(playerData != null) {
                abilities = compound(readNbt(playerData), "abilities");
                savedAt = playerData.lastModified();
            }
        }
        if(abilities != null) {
            return new Snapshot(truthy(abilities.get("mayfly")), truthy(abilities.get("flying")),
                    openedAt, savedAt);
        }

        // 3. Last resort: the world game mode (0 survival, 1 creative, 2 adventure, 3 spectator)
        Object gameType = data.get("GameType");
        if(gameType instanceof Number) {
            int mode = ((Number) gameType).intValue();
            return new Snapshot(mode == 1 || mode == 3, false, openedAt, savedAt);
        }
        return null;
    }

    private static File newestFile(File directory, String suffix) {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(suffix));
        if(files == null) return null;
        File newest = null;
        for(File file : files) {
            if(newest == null || file.lastModified() > newest.lastModified()) newest = file;
        }
        return newest;
    }

    private static boolean truthy(Object value) {
        return value instanceof Number && ((Number) value).intValue() != 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> compound(Map<String, Object> parent, String key) {
        if(parent == null) return null;
        Object child = parent.get(key);
        return child instanceof Map ? (Map<String, Object>) child : null;
    }

    // ---------------------------------------------------------------- minimal NBT reader

    private static Map<String, Object> readNbt(File file) throws IOException {
        try(InputStream in = new FileInputStream(file);
            DataInputStream data = new DataInputStream(new BufferedInputStream(new GZIPInputStream(in)))) {
            int type = data.readUnsignedByte();
            if(type != 10) throw new IOException("Not a compound");
            data.readUTF(); // root name
            return readCompound(data);
        }
    }

    private static Map<String, Object> readCompound(DataInputStream in) throws IOException {
        Map<String, Object> map = new HashMap<>();
        while(true) {
            int type = in.readUnsignedByte();
            if(type == 0) return map;
            String name = in.readUTF();
            map.put(name, readPayload(type, in));
        }
    }

    private static Object readPayload(int type, DataInputStream in) throws IOException {
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: skip(in, 1L * in.readInt()); return null;
            case 8: return in.readUTF();
            case 9: {
                int elementType = in.readUnsignedByte();
                int length = in.readInt();
                List<Object> list = new ArrayList<>();
                for(int i = 0; i < length; i++) {
                    Object element = readPayload(elementType, in);
                    if(list.size() < 64) list.add(element); // keep it small, only compounds we need are maps
                }
                return list;
            }
            case 10: return readCompound(in);
            case 11: skip(in, 4L * in.readInt()); return null;
            case 12: skip(in, 8L * in.readInt()); return null;
            default: throw new IOException("Unknown NBT tag " + type);
        }
    }

    private static void skip(DataInputStream in, long bytes) throws IOException {
        while(bytes > 0) {
            long skipped = in.skip(bytes);
            if(skipped <= 0) {
                if(in.read() < 0) throw new IOException("Unexpected end of NBT");
                skipped = 1;
            }
            bytes -= skipped;
        }
    }
}
