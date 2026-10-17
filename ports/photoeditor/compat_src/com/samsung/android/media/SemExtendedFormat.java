package com.samsung.android.media;

import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java Samsung Extended Format (SEF): the metadata trailer Samsung appends after a JPEG/PNG image
 * (re-edit data, Original_Path_Hash_Key for Revert, motion photo video, ...). Replaces the generated stub
 * (tools/patch.py skips classes that compat_src provides).
 *
 * Layout, all little-endian, at the end of the file:
 *   data blocks:  [00 00][u16 type][u32 nameLen][name][data]
 *   directory:    "SEFH" [u32 version] [u32 count] count x ([00 00][u16 type][u32 dirPos - blockPos][u32 blockLen])
 *   footer:       [u32 dirLen] "SEFT"
 * Writes rewrite only the trailer region; image bytes before it are never touched. HEIF/MP4 (SEF lives in boxes
 * there) are reported as not valid, so the app skips SEF for them.
 */
public class SemExtendedFormat {
    private static final String TAG = "SefCompat";
    private static final int VERSION = 106;
    private static final int MAX_DIR = 1 << 20;

    public static class DataPosition {
        public long offset;
        public long length;

        public DataPosition() {}
    }

    private static final class Entry {
        int type;
        String name;
        long dataOffset;   // absolute offset of the payload
        long dataLength;
        byte[] data;       // loaded lazily for rewrites
    }

    private static final class Trailer {
        long start;        // first byte of the SEF region (== file length when there is none)
        List<Entry> entries = new ArrayList<>();
    }

    public SemExtendedFormat() {}

    // ---- format detection ----

    public static boolean isValidFile(File file) throws IOException {
        if (file == null || !file.isFile()) return false;
        byte[] h = new byte[8];
        try (FileInputStream in = new FileInputStream(file)) {
            if (in.read(h) < 8) return false;
        }
        boolean jpeg = (h[0] & 0xff) == 0xFF && (h[1] & 0xff) == 0xD8;
        boolean png = (h[0] & 0xff) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G';
        return jpeg || png;
    }

    // ---- reads ----

    public static boolean hasData(File file, String keyName) throws IOException {
        return find(read(file), keyName) != null;
    }

    public static boolean hasData(File file, int dataType) throws IOException {
        for (Entry e : read(file).entries) if (e.type == dataType) return true;
        return false;
    }

    public static byte[] getData(File file, String keyName) throws IOException {
        Entry e = find(read(file), keyName);
        return e == null ? null : load(file, e);
    }

    public static int getDataType(File file, String keyName) throws IOException {
        Entry e = find(read(file), keyName);
        return e == null ? -1 : e.type;
    }

    public static int getDataCount(File file) throws IOException {
        return read(file).entries.size();
    }

    public static String[] getKeyNameArray(File file) throws IOException {
        List<Entry> es = read(file).entries;
        if (es.isEmpty()) return null;
        String[] r = new String[es.size()];
        for (int i = 0; i < r.length; i++) r[i] = es.get(i).name;
        return r;
    }

    public static int[] getDataTypeArray(File file) throws IOException {
        List<Entry> es = read(file).entries;
        if (es.isEmpty()) return null;
        int[] r = new int[es.size()];
        for (int i = 0; i < r.length; i++) r[i] = es.get(i).type;
        return r;
    }

    public static DataPosition getDataPosition(File file, String keyName) throws IOException {
        Entry e = find(read(file), keyName);
        if (e == null) return null;
        DataPosition p = new DataPosition();
        p.offset = e.dataOffset;
        p.length = e.dataLength;
        return p;
    }

    // ---- writes ----

    public static int addData(File file, String keyName, byte[] data, int dataType, int option) throws IOException {
        if (!isValidFile(file) || keyName == null || data == null) return 0;
        Trailer t = read(file);
        loadAll(file, t);
        Entry e = find(t, keyName);
        if (e == null) {
            e = new Entry();
            e.name = keyName;
            t.entries.add(e);
        }
        e.type = dataType;
        e.data = data;
        write(file, t);
        Log.i(TAG, "addData " + keyName + " (" + data.length + " bytes, type " + dataType + ") -> " + file.getName());
        return data.length > 0 ? data.length : 1;
    }

    public static int addData(File file, String keyName, File dataFile, int dataType, int option) throws IOException {
        if (dataFile == null || !dataFile.isFile()) return 0;
        return addData(file, keyName, Files.readAllBytes(dataFile.toPath()), dataType, option);
    }

    public static long addData(ParcelFileDescriptor pfd, String keyName, byte[] data, int dataType, int option)
            throws IOException {
        File f = pathOf(pfd);
        return f == null ? 0 : addData(f, keyName, data, dataType, option);
    }

    public static boolean deleteData(File file, String keyName) throws IOException {
        Trailer t = read(file);
        Entry e = find(t, keyName);
        if (e == null) return false;
        loadAll(file, t);
        t.entries.remove(e);
        write(file, t);
        Log.i(TAG, "deleteData " + keyName + " -> " + file.getName());
        return true;
    }

    public static boolean deleteAllData(File file) throws IOException {
        Trailer t = read(file);
        if (t.entries.isEmpty()) return false;
        t.entries.clear();
        write(file, t);
        return true;
    }

    public static int copyAllData(File src, File dst) throws IOException {
        Trailer s = read(src);
        if (s.entries.isEmpty() || !isValidFile(dst)) return 0;
        loadAll(src, s);
        Trailer d = read(dst);
        loadAll(dst, d);
        for (Entry e : s.entries) {
            Entry old = find(d, e.name);
            if (old != null) d.entries.remove(old);
            d.entries.add(e);
        }
        write(dst, d);
        return s.entries.size();
    }

    // ---- implementation ----

    private static Entry find(Trailer t, String keyName) {
        if (keyName == null) return null;
        for (Entry e : t.entries) if (keyName.equals(e.name)) return e;
        return null;
    }

    /** Parse the trailer; a missing or malformed trailer yields an empty one starting at EOF. */
    private static Trailer read(File file) throws IOException {
        Trailer t = new Trailer();
        if (file == null || !file.isFile()) return t;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long len = raf.length();
            t.start = len;
            if (len < 8 + 12) return t;
            byte[] foot = new byte[8];
            raf.seek(len - 8);
            raf.readFully(foot);
            if (foot[4] != 'S' || foot[5] != 'E' || foot[6] != 'F' || foot[7] != 'T') return t;
            long dirLen = le32(foot, 0);
            long dirPos = len - 8 - dirLen;
            if (dirLen < 12 || dirLen > MAX_DIR || dirPos < 0) return t;
            byte[] dir = new byte[(int) dirLen];
            raf.seek(dirPos);
            raf.readFully(dir);
            if (dir[0] != 'S' || dir[1] != 'E' || dir[2] != 'F' || dir[3] != 'H') return t;
            long count = le32(dir, 8);
            if (count < 0 || 12 + count * 12 > dirLen) return t;
            List<Entry> es = new ArrayList<>();
            long start = dirPos;
            for (int i = 0; i < count; i++) {
                int o = 12 + 12 * i;
                long blockPos = dirPos - le32(dir, o + 4);
                long blockLen = le32(dir, o + 8);
                if (blockPos < 0 || blockLen < 8 || blockPos + blockLen > dirPos) return t;
                byte[] hdr = new byte[8];
                raf.seek(blockPos);
                raf.readFully(hdr);
                long nameLen = le32(hdr, 4);
                if (nameLen < 0 || 8 + nameLen > blockLen) return t;
                byte[] name = new byte[(int) nameLen];
                raf.readFully(name);
                Entry e = new Entry();
                e.type = ((dir[o + 2] & 0xff) | (dir[o + 3] & 0xff) << 8);
                e.name = new String(name, StandardCharsets.UTF_8);
                e.dataOffset = blockPos + 8 + nameLen;
                e.dataLength = blockLen - 8 - nameLen;
                es.add(e);
                start = Math.min(start, blockPos);
            }
            t.entries = es;
            t.start = start;
        }
        return t;
    }

    private static byte[] load(File file, Entry e) throws IOException {
        if (e.data != null) return e.data;
        if (e.dataLength > Integer.MAX_VALUE) throw new IOException("SEF entry too large: " + e.name);
        byte[] b = new byte[(int) e.dataLength];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(e.dataOffset);
            raf.readFully(b);
        }
        return b;
    }

    private static void loadAll(File file, Trailer t) throws IOException {
        for (Entry e : t.entries) e.data = load(file, e);
    }

    /** Replace everything from t.start to EOF with a freshly serialized trailer (or nothing if empty). */
    private static void write(File file, Trailer t) throws IOException {
        ByteArrayBuilder out = new ByteArrayBuilder();
        long[] rel = new long[t.entries.size()];
        long[] lens = new long[t.entries.size()];
        for (int i = 0; i < rel.length; i++) {
            Entry e = t.entries.get(i);
            byte[] name = e.name.getBytes(StandardCharsets.UTF_8);
            rel[i] = out.size();
            out.u16(0).u16(e.type).u32(name.length).bytes(name).bytes(e.data);
            lens[i] = out.size() - rel[i];
        }
        if (rel.length > 0) {
            long dirStart = out.size();
            out.bytes(new byte[] {'S', 'E', 'F', 'H'}).u32(VERSION).u32(rel.length);
            for (int i = 0; i < rel.length; i++) {
                out.u16(0).u16(t.entries.get(i).type).u32(dirStart - rel[i]).u32(lens[i]);
            }
            out.u32(out.size() - dirStart).bytes(new byte[] {'S', 'E', 'F', 'T'});
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.seek(t.start);
            raf.write(out.array(), 0, out.size());
            raf.setLength(t.start + out.size());
        }
    }

    private static File pathOf(ParcelFileDescriptor pfd) {
        if (pfd == null) return null;
        try {
            File f = new File(Files.readSymbolicLink(new File("/proc/self/fd/" + pfd.getFd()).toPath()).toString());
            return f.isFile() ? f : null;
        } catch (IOException | UnsupportedOperationException e) {
            return null;
        }
    }

    private static long le32(byte[] b, int o) {
        return ByteBuffer.wrap(b, o, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xffffffffL;
    }

    private static final class ByteArrayBuilder {
        private byte[] buf = new byte[1024];
        private int n;

        private void ensure(int extra) {
            if (n + extra > buf.length) buf = java.util.Arrays.copyOf(buf, Math.max(buf.length * 2, n + extra));
        }

        ByteArrayBuilder u16(int v) {
            ensure(2);
            buf[n++] = (byte) v;
            buf[n++] = (byte) (v >> 8);
            return this;
        }

        ByteArrayBuilder u32(long v) {
            ensure(4);
            for (int i = 0; i < 4; i++) buf[n++] = (byte) (v >> (8 * i));
            return this;
        }

        ByteArrayBuilder bytes(byte[] b) {
            ensure(b.length);
            System.arraycopy(b, 0, buf, n, b.length);
            n += b.length;
            return this;
        }

        int size() { return n; }

        byte[] array() { return buf; }
    }
}
