import edu.berkeley.nlp.Test;
import jorge.PseudoWordClassifier;

import java.util.ArrayList;
import java.util.List;

public class Main {

    // This method is used to run the main test method!
    public static void main(String[] args) {
        System.out.println("Hello World!");
        Test<MockClass> test = new Test<MockClass>();
        test.main(new String[]{
                "myParameter"
        });


        // TestPseudoWordClassifier();
    }

    // Test the bucketizer
    public static void TestPseudoWordClassifier(){
        ArrayList<String> list = new ArrayList<String>() {{
            add("1934");
            add("12.3874");
            add("123,123.283");
            add("12");
            add("Jorge");
            add("jorge");
            add("J.");
            add("JORGE");
        }};
        for(String element : list){
            String bucket = PseudoWordClassifier.GetPseudoWord(element);
            System.out.format("Word: %s\t\tBucket: %s%n", element, bucket, "yay");
        }
    }
}
