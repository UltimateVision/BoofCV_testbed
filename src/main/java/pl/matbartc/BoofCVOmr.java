package pl.matbartc;

import boofcv.alg.distort.RemovePerspectiveDistortion;
import boofcv.alg.filter.binary.BinaryImageOps;
import boofcv.alg.filter.binary.Contour;
import boofcv.alg.filter.binary.GThresholdImageOps;
import boofcv.alg.filter.blur.GBlurImageOps;
import boofcv.alg.shapes.ShapeFittingOps;
import boofcv.core.image.ConvertImage;
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

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;

public class BoofCVOmr {

    // Used to bias it towards more or fewer sides. larger number = fewer sides
    static double cornerPenalty = 0.5;
    // The fewest number of pixels a side can have
    static int minSide = 10;


    public static void processPhoto(String imagePath) {
        ListDisplayPanel panel = new ListDisplayPanel();
        BufferedImage buffered = UtilImageIO.loadImage(UtilIO.pathExample(imagePath));

        panel.addImage(buffered,"Original");

        RemovePerspectiveDistortion<Planar<GrayF32>> removePerspective =
                new RemovePerspectiveDistortion<>(buffered.getWidth(), buffered.getHeight(), ImageType.pl(3, GrayF32.class));

        Planar<GrayF32> input = ConvertBufferedImage.convertFrom(buffered, true, ImageType.pl(3, GrayF32.class));

        // Specify the corners in the input image of the region.
        // Order matters! top-left, top-right, bottom-right, bottom-left
        if( !removePerspective.apply(input,
                new Point2D_F64(88, 61), new Point2D_F64(1011, 68),
                new Point2D_F64(1106, 895), new Point2D_F64(27, 913)) ){
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
        GThresholdImageOps.localSauvola(unweighted, binary,  ConfigLength.fixed(75), 0.2f, true);
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
}
