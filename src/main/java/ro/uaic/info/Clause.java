package ro.uaic.info;

import java.util.ArrayList;

public class Clause extends ArrayList<Literal> {
    public int countComplementLiterals() {
        int i = 0;

        for (Literal lit : this) {
            if (lit.isComplement()) {
                i++;
            }
        }

        return i;
    }
}
