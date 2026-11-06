package az.etiket.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NutritionLabelTest {
    @Test void explicitZeroAndMissingHaveDifferentMeanings(){
        var label=new NutritionLabel("PER_100_G",Map.of("salt",0d));
        assertEquals(0d,label.values().get("salt"));assertFalse(label.values().containsKey("sugars"));
    }
    @Test void invalidReferenceAmountsAndInconsistentValuesAreRejected(){
        assertThrows(IllegalArgumentException.class,()->new NutritionLabel("",Map.of("fat",2d)));
        assertThrows(IllegalArgumentException.class,()->new NutritionLabel("PER_SERVING",Map.of("fat",2d)));
        for(double value:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY,101})assertThrows(IllegalArgumentException.class,()->new NutritionLabel("PER_100_G",Map.of("fat",value)));
        assertThrows(IllegalArgumentException.class,()->new NutritionLabel("PER_100_ML",Map.of("fat",2d,"saturatedFat",3d)));
    }
    @Test void extractsOnlyExplicitSingleColumnValuesAndKeepsDecimalCommas(){
        var result=NutritionTextParser.parse("Per 100 g\nEnergy 840 kJ / 200 kcal\nFat 7,5 g\nSaturates 2 g\nCarbohydrates 20 g\nSugars 0 g\nProtein 4 g\nSalt 0,3 g");
        assertEquals("PER_100_G",result.nutrition().basis());assertEquals(200d,result.nutrition().values().get("energyKcal"));
        assertEquals(7.5d,result.nutrition().values().get("fat"));assertEquals(0d,result.nutrition().values().get("sugars"));assertFalse(result.nutrition().values().containsKey("fiber"));
    }
    @Test void refusesServingTablesMultipleColumnsRangesAndUnstatedUnits(){
        for(String text:List.of("Per serving 30 g\nFat 4 g","Per 100 g / per serving\nFat 10 g 3 g","Nutrition\nFat 8 g","Per 100 g\nFat <0.5 g\nSalt 0.2 0.1 g\nProtein 5"))
            assertTrue(NutritionTextParser.parse(text).nutrition().values().isEmpty(),text);
        assertTrue(NutritionTextParser.parse("Per 100 g\nSalt 500 mg\nSodium 0.2 g").nutrition().values().isEmpty());
    }
    @Test void recognizesAzerbaijaniTurkishAndRussianWithoutTranslatingOriginalText(){
        assertEquals(2.5d,NutritionTextParser.parse("100 q üçün\nYağ 2,5 q\nZülal 3 q\nDuz 0 q").nutrition().values().get("fat"));
        assertEquals(4d,NutritionTextParser.parse("100 ml\nYağ 4 g\nŞekerler 5 g\nProtein 3 g").nutrition().values().get("fat"));
        assertEquals(3d,NutritionTextParser.parse("На 100 г\nБелки 3 г\nЖиры 2 г\nСоль 0,1 г").nutrition().values().get("protein"));
    }
    @Test void readsGermanRowsWithAnExplicitReferenceAndSeparateEnergyUnits() {
        var result = NutritionTextParser.parse("""
            Nährwerte in 100g
            Brennwert 1595 kJ
            (378 kcal)
            Fett 9,8 g
            davon gesättigte
            Fettsäuren 3,3 g
            Kohlenhydrate 66 g
            davon Zucker 27 g
            Ballaststoffe 2,7 g
            Eiweiß 5,2 g
            Salz 0,29 g
            """);
        assertEquals(new NutritionLabel("PER_100_G", Map.of(
            "energyKcal", 378d, "fat", 9.8d, "saturatedFat", 3.3d, "carbohydrates", 66d,
            "sugars", 27d, "fiber", 2.7d, "protein", 5.2d, "salt", .29d)), result.nutrition());
    }

    @Test void refusesValuesWhenTheServingColumnIsDetectedEvenIfSomeRowsLostANumber() {
        for (String raw : List.of(
            "Nährwerte in 100g in 22g\nFett 2,1 g\ndavon Zucker 5,9 g",
            "Nährwerte in 100g\n(1Portion)\nFett 2,1 g",
            "Nährwerte in100g 22g\nFett 2,1 g\ndavon Zucker 5,9 g",
            "Per 100 g / per 100 g\nFat 2,1 g",
            "Per 100 g\nFat 9.8 g 2.1 g\nSugar 5.9 g")) {
            assertTrue(NutritionTextParser.parse(raw).nutrition().values().isEmpty(), raw);
        }
        assertTrue(NutritionTextParser.parse("Carbohydrates 100 g\nSalt 0.2 g").nutrition().values().isEmpty());
    }

    @Test void per100mlIsPreservedWithoutMisleadingOlderClients(){
        var old=DemoCatalog.products().get(0);
        var p=new Product(old.barcode(),old.name(),old.brand(),old.quantity(),old.ingredients(),old.ingredientsLanguage(),"",Map.of(),List.of(),List.of(),"REVIEWED_LABEL","","","",false,null,null,new NutritionLabel("PER_100_ML",Map.of("protein",3d)));
        assertTrue(p.nutrients().isEmpty());assertEquals(3d,p.nutrition().values().get("protein"));
    }
}
