package ro.uaic.info.tubb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import ro.uaic.info.cnf.Clause;

class LearntClausesTest {

    @Test
    void rejectsLongDuplicateAndOverflowingClauses() {
        LearntClauses db = new LearntClauses(2, 2);
        assertTrue(db.add(Clause.of(1, -2)));
        assertFalse(db.add(Clause.of(-2, 1)), "same literal set is a duplicate");
        assertFalse(db.add(Clause.of(1, 2, 3)), "too long");
        assertTrue(db.add(Clause.of(3)));
        assertFalse(db.add(Clause.of(4)), "database full");
        assertEquals(2, db.size());
        assertArrayEquals(new Clause[] {Clause.of(1, -2), Clause.of(3)}, db.snapshot());
    }

    @Test
    void snapshotsAreStable() {
        LearntClauses db = new LearntClauses(5, 10);
        db.add(Clause.of(1));
        Clause[] first = db.snapshot();
        db.add(Clause.of(2));
        assertEquals(1, first.length);
        assertEquals(2, db.snapshot().length);
    }
}
