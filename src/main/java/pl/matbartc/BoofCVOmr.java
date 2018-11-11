package pl.matbartc;

import boofcv.abst.fiducial.QrCodeDetector;
import boofcv.alg.distort.RemovePerspectiveDistortion;
import boofcv.alg.fiducial.qrcode.QrCode;
import boofcv.alg.filter.binary.BinaryImageOps;
import boofcv.alg.filter.binary.Contour;
import boofcv.alg.filter.binary.GThresholdImageOps;
import boofcv.alg.filter.blur.GBlurImageOps;
import boofcv.alg.shapes.ShapeFittingOps;
import boofcv.core.image.ConvertImage;
import boofcv.factory.fiducial.FactoryFiducial;
import boofcv.gui.ListDisplayPanel;
import boofcv.gui.binary.VisualizeBinaryData;
import boofcv.gui.feature.VisualizeShapes;
import boofcv.gui.image.ShowImages;
import boofcv.io.UtilIO;
import boofcv.io.image.ConvertBufferedImage;
import boofcv.io.image.UtilImageIO;
import boofcv.struct.ConfigLength;
import boofcv.struct.ConnectRule;
import boofcv.struct.PointIndex_I32;
import boofcv.struct.image.GrayF32;
import boofcv.struct.image.GrayU8;
import boofcv.struct.image.ImageType;
import boofcv.struct.image.Planar;
import georegression.struct.point.Point2D_F64;
import org.ddogleg.struct.FastQueue;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.WritableRaster;
import java.util.HashMap;
import java.util.List;

public class BoofCVOmr {

    // Used to bias it towards more or fewer sides. larger number = fewer sides
    static double cornerPenalty = 0.1;
    // The fewest number of pixels a side can have
    static int minSide = 7;

    static BufferedImage deepCopy(BufferedImage bi) {
        ColorModel cm = bi.getColorModel();
        boolean isAlphaPremultiplied = cm.isAlphaPremultiplied();
        WritableRaster raster = bi.copyData(null);
        return new BufferedImage(cm, raster, isAlphaPremultiplied, null);
    }

    public static void processPhoto(String imagePath) {
        ListDisplayPanel panel = new ListDisplayPanel();
        BufferedImage buffered = UtilImageIO.loadImage(UtilIO.pathExample(imagePath));
        BufferedImage bufferedCopy = deepCopy(buffered);

        panel.addImage(buffered,"Original");
        HashMap<String, Point2D_F64> fiducials = detectFiducials(panel, bufferedCopy);

        Planar<GrayF32> input = ConvertBufferedImage.convertFrom(buffered, true, ImageType.pl(3, GrayF32.class));
        RemovePerspectiveDistortion<Planar<GrayF32>> removePerspective =
                new RemovePerspectiveDistortion<>(
                        dist(fiducials.get("TL"), fiducials.get("TR")),
                        dist(fiducials.get("TR"), fiducials.get("BR")),
                        ImageType.pl(3, GrayF32.class));

        // Specify the corners in the input image of the region.
        // Order matters! top-left, top-right, bottom-right, bottom-left
//        if( !removePerspective.apply(input,
////                new Point2D_F64(88, 61), new Point2D_F64(1011, 68),
////                new Point2D_F64(1106, 895), new Point2D_F64(27, 913)) ){
////            throw new RuntimeException("Failed!?!?");
////        }

        if( !removePerspective.apply(input,
                fiducials.get("TL"), fiducials.get("TR"),
                fiducials.get("BR"), fiducials.get("BL")) ){
            throw new RuntimeException("Failed!?!?");
        }

        Planar<GrayF32> undistorted = removePerspective.getOutput();
        panel.addImage(undistorted, "Without Perspective Distortion");

        Planar<GrayF32> blurred = undistorted.createSameShape();

        // size of the blur kernel. square region with a width of radius*2 + 1
        int radius = 3;

        // Apply gaussian blur using a procedural interface
        GBlurImageOps.gaussian(undistorted,blurred,-1,radius,null);
        panel.addImage(ConvertBufferedImage.convertTo(blurred, null, true),"Gaussian");

        GrayF32 unweighted = new GrayF32(blurred.width, blurred.height);
        ConvertImage.average(blurred,unweighted);

        GrayU8 binary = new GrayU8(blurred.width,blurred.height);
        GThresholdImageOps.localSauvola(unweighted, binary,  ConfigLength.fixed(50), 0.2f, true);
        panel.addImage(VisualizeBinaryData.renderBinary(binary, false, null),"Local: Sauvola");

        // Find the contour around the shapes
        List<Contour> contours = BinaryImageOps.contour(binary, ConnectRule.EIGHT,null);

        // Fit a polygon to each shape and draw the results
        BufferedImage polygon = new BufferedImage(input.width,input.height,BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = polygon.createGraphics();
        g2.setStroke(new BasicStroke(2));

        for( Contour c : contours ) {
            // Fit the polygon to the found external contour.  Note loop = true
            List<PointIndex_I32> vertexes = ShapeFittingOps.fitPolygon(c.external,true, minSide,cornerPenalty);

            g2.setColor(Color.RED);
            VisualizeShapes.drawPolygon(vertexes,true,g2);

//            // handle internal contours now
//            g2.setColor(Color.BLUE);
//            for( List<Point2D_I32> internal : c.internal ) {
//                vertexes = ShapeFittingOps.fitPolygon(internal,true, minSide,cornerPenalty);
//                VisualizeShapes.drawPolygon(vertexes,true,g2);
//            }
        }

        panel.addImage(polygon, "Binary Blob Contours");

        JFrame window = ShowImages.showWindow(panel,"OMR PoC",true);
        window.setVisible(true);
    }

    private static HashMap<String, Point2D_F64> detectFiducials(ListDisplayPanel panel, BufferedImage buffered) {
        HashMap<String, Point2D_F64> fiducials = new HashMap<>();

        GrayU8 gray = ConvertBufferedImage.convertFrom(buffered,(GrayU8)null);

        QrCodeDetector<GrayU8> detector = FactoryFiducial.qrcode(null,GrayU8.class);
        detector.process(gray);

        // Get's a list of all the qr codes it could successfully detect and decode
        List<QrCode> detections = detector.getDetections();

        Graphics2D g2 = buffered.createGraphics();
        int strokeWidth = Math.max(4,buffered.getWidth()/200); // in large images the line can be too thin
        g2.setColor(Color.GREEN);
        g2.setStroke(new BasicStroke(strokeWidth));
        for( QrCode qr : detections ) {
            // The message encoded in the marker
            System.out.println("message: " + qr.message + " bounds: " + qr.bounds.toString());

            fiducials.put(qr.message, calculateCenterOfMass2D(qr.bounds.vertexes));

            // Visualize its location in the image
            VisualizeShapes.drawPolygon(qr.bounds,true,1,g2);
        }

        // List of objects it thinks might be a QR Code but failed for various reasons
        List<QrCode> failures = detector.getFailures();
        g2.setColor(Color.RED);
        for( QrCode qr : failures ) {
            // If the 'cause' is ERROR_CORRECTION or later then it's probably a real QR Code that
            if( qr.failureCause.ordinal() < QrCode.Failure.ERROR_CORRECTION.ordinal() )
                continue;

            VisualizeShapes.drawPolygon(qr.bounds,true,1,g2);
        }

        panel.addImage(buffered,"Example QR Codes");
        return fiducials;
    }

    private static Point2D_F64 calculateCenterOfMass2D(FastQueue<Point2D_F64> vertexes) {
        Point2D_F64 centerOfMass = new Point2D_F64();
        for(int i = 0; i < vertexes.size; i++) {
            Point2D_F64 p = vertexes.get(i);
            centerOfMass.x += p.x;
            centerOfMass.y += p.y;
        }

        centerOfMass.x /= vertexes.size;
        centerOfMass.y /= vertexes.size;

        return centerOfMass;
    }

    private static int dist(Point2D_F64 p1, Point2D_F64 p2) {
        return (int) Math.round(Math.sqrt(Math.pow(p1.x - p2.x, 2d) + Math.pow(p1.y - p2.y, 2d)));
    }
}
