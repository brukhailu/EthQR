package com.example.EthQR.service;

import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.QRCodeDetector;

import java.io.IOException;
import java.io.InputStream;

public class QRDetector {

    static {
        // Load the OpenCV native library
        nu.pattern.OpenCV.loadShared();
    }

    public String detectAndDecode(InputStream inputStream) throws IOException {
        // Read image into a byte array
        byte[] bytes = inputStream.readAllBytes();
        Mat image = Imgcodecs.imdecode(new MatOfByte(bytes), Imgcodecs.IMREAD_COLOR);

        if (image.empty()) {
            return null;
        }

        QRCodeDetector qrCodeDetector = new QRCodeDetector();

        // 1. Try decoding the original image
        String decodedText = qrCodeDetector.detectAndDecode(image);
        if (decodedText != null && !decodedText.isEmpty()) {
            return decodedText;
        }

        // 2. Pre-process for better results (Grayscale + Thresholding)
        Mat gray = new Mat();
        Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY);

        // Try on grayscale
        decodedText = qrCodeDetector.detectAndDecode(gray);
        if (decodedText != null && !decodedText.isEmpty()) {
            return decodedText;
        }

        // Try with CLAHE (Contrast Limited Adaptive Histogram Equalization) for low contrast
        Mat enhanced = new Mat();
        org.opencv.imgproc.CLAHE clahe = Imgproc.createCLAHE(2.0, new org.opencv.core.Size(8, 8));
        clahe.apply(gray, enhanced);
        decodedText = qrCodeDetector.detectAndDecode(enhanced);
        if (decodedText != null && !decodedText.isEmpty()) {
            return decodedText;
        }

        // Try with simple thresholding
        Mat thresh = new Mat();
        Imgproc.threshold(gray, thresh, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
        decodedText = qrCodeDetector.detectAndDecode(thresh);

        return decodedText;
    }
}
