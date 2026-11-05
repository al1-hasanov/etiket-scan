package az.etiket.backend;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

class TesseractOcrTest {
    @Test void tsvKeepsLineOrderAndUncertainWordsWithoutInventingText(){
        String tsv="level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n"
            +"5\t1\t1\t1\t1\t1\t1\t1\t1\t1\t96\tMilk\n"
            +"5\t1\t1\t1\t1\t2\t1\t1\t1\t1\t50\tsalt\n"
            +"5\t1\t1\t1\t2\t1\t1\t1\t1\t1\t90\tWater\n";
        var text=TesseractOcr.parse(tsv,90);assertEquals("Milk salt\nWater",text.text());assertEquals(java.util.List.of("salt"),text.uncertainWords());assertEquals(90,text.rotation());
    }
    @Test @EnabledIfEnvironmentVariable(named="ETIKET_OCR_ENGINE_TEST",matches="true")
    void realEngineReadsFiveLanguagesAndRotatedTextAndHandlesBlankPhotos()throws Exception{
        var engine=new TesseractOcr("tesseract",35);
        Map<String,String> labels=new LinkedHashMap<>();
        labels.put("en","Ingredients: wheat flour, milk, sugar, salt.");
        labels.put("az","Tərkibi: inək südü, şəkər, bitki yağı, duz.");
        labels.put("tr","İçindekiler: buğday unu, süt, şeker, tuz.");
        labels.put("ru","Состав: молоко, сахар, пшеничная мука, соль.");
        labels.put("de","Zutaten: Wasser, Zucker, Fruktose, Säuerungsmittel.");
        for(var entry:labels.entrySet()){
            var result=engine.recognize(image(entry.getValue(),false),entry.getKey());
            assertTrue(result.text().length()>20,entry.getKey()+": "+result);assertTrue(result.confidence()>45,entry.getKey()+": "+result);
            String expected=switch(entry.getKey()){case "az"->"duz";case "tr"->"tuz";case "ru"->"сахар";case "de"->"fruktose";default->"sugar";};
            assertTrue(result.text().toLowerCase(Locale.ROOT).contains(expected),result.toString());
            assertTrue(result.warnings().isEmpty(),result.toString());
        }
        var rotated=engine.recognize(image("Ingredients: wheat flour, milk, sugar, salt.",true),"en");assertTrue(rotated.text().toLowerCase(Locale.ROOT).contains("sugar"),rotated.toString());
        var blank=engine.recognize(image("",false),"en");
        assertTrue(blank.text().isBlank());
        assertTrue(blank.warnings().contains("RETAKE_UNREADABLE_TEXT"));
    }
    private byte[] image(String text,boolean rotate)throws Exception{
        BufferedImage image=new BufferedImage(1800,250,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,1800,250);g.setColor(Color.BLACK);g.setFont(new Font("DejaVu Sans",Font.PLAIN,42));g.drawString(text,40,100);g.dispose();
        if(rotate){BufferedImage next=new BufferedImage(250,1800,BufferedImage.TYPE_INT_RGB);var r=next.createGraphics();r.translate(250,0);r.rotate(Math.PI/2);r.drawImage(image,0,0,null);r.dispose();image=next;}
        ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();
    }
}
