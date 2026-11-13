package tricatch.oehostsapp.tools;

import tricatch.oehostsapp.ui.Ui;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the installers' app icons in {@code packaging/} from {@link Ui#appIcon}, the same
 * drawing as the window and Dock icons ({@code ./gradlew icons}). Run it whenever that drawing or
 * the logo changes; the installers fetch these files from master.
 * <ul>
 * <li>{@code linux/oehostsapp.png}: 256px, framed (white rounded square)</li>
 * <li>{@code macos/oehostsapp.icns}: framed, PNG entries from 32 to 1024px</li>
 * <li>{@code windows/oehostsapp.ico}: unframed (the round logo filling the icon); sizes below 256
 *     as 32-bit BMP, because Windows decodes small PNG entries wrongly, and 256 as PNG</li>
 * </ul>
 */
public final class AppIcons {

    private AppIcons() {}

    /** Argument: the packaging directory. */
    public static void main(String[] args) throws IOException {
        System.setProperty("java.awt.headless", "true");
        if (args.length != 1) throw new IllegalArgumentException("usage: AppIcons <packaging dir>");
        Path dir = Path.of(args[0]);
        write(dir.resolve("linux/oehostsapp.png"), png(256, true));
        write(dir.resolve("macos/oehostsapp.icns"), icns());
        write(dir.resolve("windows/oehostsapp.ico"), ico());
    }

    private static void write(Path file, byte[] data) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, data);
        System.out.println("wrote " + file + " (" + data.length + " bytes)");
    }

    static byte[] png(int size, boolean framed) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(Ui.appIcon(size, framed), "png", out);
        return out.toByteArray();
    }

    /** An .icns of PNG entries: ic11 32, ic12 64 (the @2x of 16 and 32), ic07 128, ic13 256, ic08 256, ic14 512, ic09 512, ic10 1024. */
    static byte[] icns() throws IOException {
        String[] types = {"ic11", "ic12", "ic07", "ic13", "ic08", "ic14", "ic09", "ic10"};
        int[] sizes = {32, 64, 128, 256, 256, 512, 512, 1024};
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream b = new DataOutputStream(body);
        for (int i = 0; i < types.length; i++) {
            byte[] p = png(sizes[i], true);
            b.writeBytes(types[i]);
            b.writeInt(p.length + 8);
            b.write(p);
        }
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        DataOutputStream a = new DataOutputStream(all);
        a.writeBytes("icns");
        a.writeInt(body.size() + 8);
        a.write(body.toByteArray());
        return all.toByteArray();
    }

    /** An .ico with the sizes Windows asks for at 100% to 200% scaling. */
    static byte[] ico() throws IOException {
        int[] sizes = {16, 20, 24, 32, 40, 48, 64, 128, 256};
        List<byte[]> images = new ArrayList<>();
        for (int s : sizes) images.add(s == 256 ? png(s, false) : bmp(Ui.appIcon(s, false)));
        int offset = 6 + 16 * sizes.length;
        ByteBuffer h = ByteBuffer.allocate(offset).order(ByteOrder.LITTLE_ENDIAN);
        h.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        for (int i = 0; i < sizes.length; i++) {
            int s = sizes[i];
            // Width and height 0 mean 256.
            h.put((byte) (s >= 256 ? 0 : s)).put((byte) (s >= 256 ? 0 : s)).put((byte) 0).put((byte) 0);
            h.putShort((short) 1).putShort((short) 32);
            h.putInt(images.get(i).length).putInt(offset);
            offset += images.get(i).length;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(h.array());
        for (byte[] img : images) out.write(img);
        return out.toByteArray();
    }

    /**
     * An icon BMP: BITMAPINFOHEADER with the height doubled (image plus mask), BGRA rows bottom-up,
     * then the AND mask, left all zero because the alpha channel carries the transparency.
     */
    static byte[] bmp(BufferedImage img) {
        int s = img.getWidth();
        int maskRow = ((s + 31) / 32) * 4;
        ByteBuffer b = ByteBuffer.allocate(40 + s * s * 4 + maskRow * s).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(40).putInt(s).putInt(s * 2).putShort((short) 1).putShort((short) 32)
                .putInt(0).putInt(s * s * 4 + maskRow * s).putInt(0).putInt(0).putInt(0).putInt(0);
        for (int y = s - 1; y >= 0; y--) {
            for (int x = 0; x < s; x++) {
                int argb = img.getRGB(x, y);
                b.put((byte) argb).put((byte) (argb >> 8)).put((byte) (argb >> 16)).put((byte) (argb >>> 24));
            }
        }
        return b.array();
    }
}
