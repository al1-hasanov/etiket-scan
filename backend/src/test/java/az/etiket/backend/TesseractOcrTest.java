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
    @Test
    @EnabledIfEnvironmentVariable(named="ETIKET_OCR_ENGINE_TEST", matches="true")
    void readsLightNutritionTextInsideADarkLabel() throws Exception {
        BufferedImage label = new BufferedImage(600, 260, BufferedImage.TYPE_INT_RGB);
        var graphics = label.createGraphics();
        graphics.setColor(new Color(24, 42, 79));
        graphics.fillRect(0, 0, label.getWidth(), label.getHeight());
        graphics.setColor(new Color(247, 239, 221));
        graphics.drawRect(16, 14, 568, 231);
        graphics.drawLine(16, 62, 584, 62);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setFont(new Font("DejaVu Sans", Font.PLAIN, 24));
        graphics.drawString("Nährwerte pro", 28, 45);
        graphics.drawString("100 g", 492, 45);
        graphics.drawString("Energie", 28, 95);
        graphics.drawString("418 kcal", 468, 95);
        graphics.drawString("Fett", 28, 131);
        graphics.drawString("21 g", 508, 131);
        graphics.drawString("davon Zucker", 28, 167);
        graphics.drawString("36 g", 508, 167);
        graphics.drawString("Salz", 28, 203);
        graphics.drawString("0,90 g", 480, 203);
        graphics.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(label, "jpeg", bytes);
        label.flush();

        var result = new TesseractOcr("tesseract", 35).recognize(bytes.toByteArray(), "de");

        assertTrue(result.warnings().isEmpty(), result.toString());
        String compact = result.text().replace(" ", "");
        assertTrue(compact.contains("100g"), result.toString());
        assertTrue(compact.contains("418kcal"), result.toString());
        assertTrue(result.text().contains("Zucker"), result.toString());
        assertTrue(compact.contains("36g"), result.toString());
    }

    @Test
    @EnabledIfEnvironmentVariable(named="ETIKET_OCR_ENGINE_TEST", matches="true")
    void readsPer100ValuesFromAWholeGermanNutritionTable() throws Exception {
        BufferedImage label = new BufferedImage(1100, 400, BufferedImage.TYPE_INT_RGB);
        var graphics = label.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, label.getWidth(), label.getHeight());
        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font("DejaVu Sans", Font.PLAIN, 36));
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        String[][] rows = {
            {"Nährwerte", "pro 100 g", "pro 22 g"},
            {"Brennwert", "378 kcal", "85 kcal"},
            {"Fett", "9,8 g", "2,1 g"},
            {"Kohlenhydrate", "66 g", "15 g"},
            {"Eiweiß", "5,2 g", "1,1 g"},
            {"Salz", "0,29 g", "0,06 g"}
        };
        for (int i = 0; i < rows.length; i++) {
            int baseline = 52 + 57 * i;
            graphics.drawString(rows[i][0], 35, baseline);
            graphics.drawString(rows[i][1], 750 - graphics.getFontMetrics().stringWidth(rows[i][1]), baseline);
            graphics.drawString(rows[i][2], 1040 - graphics.getFontMetrics().stringWidth(rows[i][2]), baseline);
        }
        graphics.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(label, "png", bytes);
        label.flush();

        var result = new TesseractOcr("tesseract", 35).recognizeNutrition(bytes.toByteArray(), "de");
        assertTrue(result.warnings().isEmpty(), result.toString());
        assertTrue(result.text().replace(" ", "").contains("85kcal"), result.toString());
        assertNotNull(result.tableNutrition(), result.toString());
        var nutrition = result.tableNutrition().nutrition();
        assertEquals("PER_100_G", nutrition.basis(), result.toString());
        assertEquals(378d, nutrition.values().get("energyKcal"));
        var expected = Map.of("energyKcal", 378d, "fat", 9.8d, "carbohydrates", 66d, "protein", 5.2d, "salt", .29d);
        nutrition.values().forEach((key, value) -> assertEquals(expected.get(key), value, key));
    }

    private byte[] image(String text,boolean rotate)throws Exception{
        BufferedImage image=new BufferedImage(1800,250,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,1800,250);g.setColor(Color.BLACK);g.setFont(new Font("DejaVu Sans",Font.PLAIN,42));g.drawString(text,40,100);g.dispose();
        if(rotate){BufferedImage next=new BufferedImage(250,1800,BufferedImage.TYPE_INT_RGB);var r=next.createGraphics();r.translate(250,0);r.rotate(Math.PI/2);r.drawImage(image,0,0,null);r.dispose();image=next;}
        ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();
    }
}
