package tricatch.oehubapp.ui;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import javax.swing.*;
import java.awt.*;
import java.util.Map;

/** A square QR code of a short text (a URL), drawn module by module with a white quiet zone. */
final class QrCode extends JComponent {

    private final int size;
    private BitMatrix matrix;

    QrCode(int size) {
        this.size = size;
        setOpaque(true);
        setBackground(Color.WHITE);
    }

    /** Shows {@code text}, or nothing when it is null. */
    void setText(String text) {
        matrix = null;
        if (text != null) {
            try {
                // Size 0: the writer returns one bit per module; the quiet zone is 2 modules.
                matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                        Map.of(EncodeHintType.MARGIN, 2, EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M));
            } catch (WriterException e) {
                matrix = null;
            }
        }
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(size, size);
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics g) {
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, getWidth(), getHeight());
        if (matrix == null) return;
        int n = matrix.getWidth();
        // Whole pixels per module keep the edges sharp; the code is centred in what is left.
        int cell = Math.max(1, Math.min(getWidth(), getHeight()) / n);
        int x0 = (getWidth() - cell * n) / 2, y0 = (getHeight() - cell * n) / 2;
        g.setColor(Color.BLACK);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (matrix.get(x, y)) g.fillRect(x0 + x * cell, y0 + y * cell, cell, cell);
            }
        }
    }
}
