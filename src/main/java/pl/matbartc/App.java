package pl.matbartc;

/**
 * Hello world!
 *
 */
public class App 
{
    public static void main( String[] args )
    {
        String imagePath = args[0];
        if(imagePath != null && imagePath.length() > 0) {
            BoofCVOmr.processPhoto(imagePath);
        } else {
            System.out.println("Image path appears to be empty...");
        }
    }
}
