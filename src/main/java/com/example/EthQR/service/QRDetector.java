package com.example.EthQR.service;

import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;

@Service
public class QRDetector {

    static {
        // Use loadLocally() for Java 12+
        nu.pattern.OpenCV.loadLocally();
    }

    public String detectAndDecode(InputStream inputStream) throws IOException {
        byte[] bytes = inputStream.readAllBytes();
        
        // Try ZXing directly first (most reliable for ECI/Standard QR)
        String result = decodeWithZXing(bytes);
        if (result != null) return result;

        // If direct decode fails, try OpenCV preprocessing
        Mat image = Imgcodecs.imdecode(new MatOfByte(bytes), Imgcodecs.IMREAD_COLOR);
        if (image.empty()) return null;

        // 1. Try Grayscale
        Mat gray = new Mat();
        Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY);
        result = decodeMatWithZXing(gray);
        if (result != null) return result;

        // 2. Try CLAHE (Contrast Enhancement)
        Mat enhanced = new Mat();
        org.opencv.imgproc.CLAHE clahe = Imgproc.createCLAHE(2.0, new org.opencv.core.Size(8, 8));
        clahe.apply(gray, enhanced);
        result = decodeMatWithZXing(enhanced);
        if (result != null) return result;

        // 3. Try Otsu Thresholding
        Mat thresh = new Mat();
        Imgproc.threshold(gray, thresh, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
        result = decodeMatWithZXing(thresh);
        
        return result;
    }

    /**
     * Generates a QR code image for the given content.
     * @param content The string content to encode in the QR code.
     * @param width The width of the QR code image.
     * @param height The height of the QR code image.
     * @return A byte array containing the PNG image data of the QR code.
     * @throws WriterException If an error occurs during QR code generation.
     * @throws IOException If an error occurs during image writing.
     */
    public byte[] generateQrImage(String content, int width, int height) throws WriterException, IOException {
        QRCodeWriter qrCodeWriter = new QRCodeWriter();
        BitMatrix bitMatrix = qrCodeWriter.encode(content, BarcodeFormat.QR_CODE, width, height);

        BufferedImage bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                bufferedImage.setRGB(x, y, bitMatrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF); // Black and White
            }
        }

        ByteArrayOutputStream pngOutputStream = new ByteArrayOutputStream();
        ImageIO.write(bufferedImage, "PNG", pngOutputStream);
        return pngOutputStream.toByteArray();
    }

    private String decodeWithZXing(byte[] imageBytes) {
        try {
            BufferedImage bufferedImage = ImageIO.read(new ByteArrayInputStream(imageBytes));
            return decodeBufferedImage(bufferedImage);
        } catch (Exception e) {
            return null;
        }
    }

    private String decodeMatWithZXing(Mat mat) {
        try {
            MatOfByte mob = new MatOfByte();
            Imgcodecs.imencode(".png", mat, mob);
            BufferedImage bufferedImage = ImageIO.read(new ByteArrayInputStream(mob.toArray()));
            return decodeBufferedImage(bufferedImage);
        } catch (Exception e) {
            return null;
        }
    }

    private String decodeBufferedImage(BufferedImage image) {
        if (image == null) return null;
        try {
            LuminanceSource source = new BufferedImageLuminanceSource(image);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            hints.put(DecodeHintType.POSSIBLE_FORMATS, java.util.List.of(BarcodeFormat.QR_CODE));
            
            Result result = new MultiFormatReader().decode(bitmap, hints);
            return result.getText();
        } catch (NotFoundException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
