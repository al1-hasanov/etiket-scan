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
    @Test void per100mlIsPreservedWithoutMisleadingOlderClients(){
        var old=DemoCatalog.products().get(0);
        var p=new Product(old.barcode(),old.name(),old.brand(),old.quantity(),old.ingredients(),old.ingredientsLanguage(),"",Map.of(),List.of(),List.of(),"REVIEWED_LABEL","","","",false,null,null,new NutritionLabel("PER_100_ML",Map.of("protein",3d)));
        assertTrue(p.nutrients().isEmpty());assertEquals(3d,p.nutrition().values().get("protein"));
    }
}
