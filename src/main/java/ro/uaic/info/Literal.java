package ro.uaic.info;

import java.util.Objects;

public class Literal implements Comparable<Literal> {
    private Integer value;

    private Integer sign = 1;

    Literal(Integer item) {
        value = Math.abs(item);
        sign = item < 0 ? -1 : 1;
    }

    public Integer index(boolean extended) {
        if (extended) {
            return sign == 1 ? (value - 1) : (2 * value - 1);
        }

        return value - 1;
    }

    public Integer index() {
        return index(false);
    }

    public Integer sign() {
        return sign;
    }

    public boolean isComplement() {
        return sign == -1;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Literal)) return false;
        Literal literal = (Literal) o;
        return Objects.equals(value, literal.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public int compareTo(Literal o) {
        return Integer.compare(o.value, this.value);
    }
}
