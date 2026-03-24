package com.example.EthQR.service;

import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
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

        // Use OpenCV's built-in QRCodeDetector
        QRCodeDetector qrCodeDetector = new QRCodeDetector();
        String decodedText = qrCodeDetector.detectAndDecode(image);

        return decodedText;
    }
}
