# A Total Unimodularity Based Branch-and-Bound Method for Integer Programming

[![CI](https://github.com/afurculita/IP-BranchAndBound/actions/workflows/ci.yml/badge.svg)](https://github.com/afurculita/IP-BranchAndBound/actions/workflows/ci.yml)

A Java implementation of the SAT solver described in
[*A Total Unimodularity Based Branch-and-Bound Method for Integer Programming*](./docs/paper.pdf)
by Jun Wang, Mei Long and Duan Li (APCCAS 2006).

The paper models a CNF formula as a 0-±1 integer program whose optimal value is zero iff the formula is
satisfiable, and solves it by branch-and-bound. Its contribution is the **branching rule**: variables are
ranked by how much *non-total-unimodular* structure they remove from the constraint matrix when fixed.
Fixing those variables first tends to leave a totally unimodular (TU) matrix, whose linear relaxation has
integral vertices, so the search terminates after few nodes.

* [The method](#the-method)
* [Building and running](#building-and-running)
* [Using it as a library](#using-it-as-a-library)
* [Tests](#tests)
* [Benchmarks](#benchmarks)
* [Design notes](#design-notes)
* [Limitations and possible improvements](#limitations-and-possible-improvements)

## The method

### Integer programming model (Section II of the paper)

A clause is turned into a linear inequality by replacing `∨` with `+` and a complement `¬xⱼ` with `1 − xⱼ`.
For a formula with `n` variables and `m` clauses this gives the 0-±1 matrix `A` (`aᵢⱼ = +1` if clause `i`
contains `xⱼ`, `−1` if it contains `¬xⱼ`, `0` otherwise) and the vector `b` (`bᵢ` = number of complement
literals of clause `i`). With one binary slack `sᵢ` per clause:

```
(IP1)   min  1ᵀs
        s.t. A x + I s ≥ 1 − b,     x ∈ {0,1}ⁿ,  s ∈ {0,1}ᵐ
```

**Theorem 1.** The formula is satisfiable iff the optimal value of (IP1) is zero; an optimal solution with
value zero is a satisfying assignment.

A second model, (IP2), uses a 0-1 matrix over the `2n` literals `(x, x̄)` with the coupling constraints
`x + x̄ = 1`. It is only used to rank variables (below).

### Non-TU degrees (Section III)

* A 2×2 0-±1 matrix is not TU iff one row has equal entries and the other opposite entries. For two columns
  `i, j` of the (IP1) matrix let `e¹ᵢⱼ` be the number of rows equal to `(1,1)` or `(−1,−1)` and `e²ᵢⱼ` the
  number of rows equal to `(1,−1)` or `(−1,1)`. The pair's non-TU degree is `eᵢⱼ = e¹ᵢⱼ · e²ᵢⱼ`, the number
  of non-TU 2×2 submatrices it spans.
* A 3×3 0-1 matrix is not TU iff every row and column has exactly one zero. For three columns of the (IP2)
  matrix, `e¹ᵢⱼₖ, e²ᵢⱼₖ, e³ᵢⱼₖ` count the rows equal to `(0,1,1), (1,0,1), (1,1,0)` and
  `eᵢⱼₖ = e¹ᵢⱼₖ · e²ᵢⱼₖ · e³ᵢⱼₖ`.

The non-TU degree of a variable is `wᵢ = Σⱼ eᵢⱼ` (or `Σⱼₖ eᵢⱼₖ`).

### Procedure 1: branching order `S`

1. Build (IP1), compute all `eᵢⱼ`.
2. While some `wᵢ > 0`: rank `i* = argmax wᵢ`, append it to `S`, set all `eᵢ*ⱼ = 0`, recompute `w`.
3. Build (IP2) for the *reduced* problem over the variables not in `S`, compute all `eᵢⱼₖ`, and repeat the
   greedy ranking with the triple degrees.
4. Variables still unranked (all degrees zero) follow in an arbitrary order (we use increasing index).

### Algorithm 1: TU-based branch-and-bound

Width-first search over the variables in the order `S`. At every node the **linear relaxation** of the
sub-problem is solved:

* value `> 0` → the integer optimum is at least 1, the node is pruned;
* value `= 0` and the solution is integral → it is a satisfying assignment, stop with **SATISFIABLE**;
* value `= 0` and fractional → branch on the next variable of `S`.

If no active node remains the formula is **UNSATISFIABLE**.

### Worked example (paper, equation (2))

`F = (x₁ ∨ x₂) ∧ (¬x₁ ∨ x₂ ∨ ¬x₃) ∧ (¬x₂ ∨ ¬x₄)` gives

```
      ⎡ 1  1  0  0 ⎤        ⎡0⎤
A  =  ⎢−1  1 −1  0 ⎥ ,  b = ⎢2⎥ ,   x₁ + x₂ ≥ 1,  −x₁ + x₂ − x₃ ≥ −1,  −x₂ − x₄ ≥ −1
      ⎣ 0 −1  0 −1 ⎦        ⎣2⎦
```

Columns 1 and 2 share a `(1,1)` row and a `(−1,1)` row, so `e₁₂ = 1`; every other pair has degree 0. The
branching order is therefore `x₁` first, then (no non-TU triple exists in the reduced (IP2)) `x₂, x₃, x₄`.
This example is encoded in [`src/test/resources/cnf/paper-example.cnf`](src/test/resources/cnf/paper-example.cnf)
and checked in `Ip1FormulationTest` and `BranchingOrderTest`.

## Building and running

Requirements: **JDK 21+** and **Maven 3.9+**.

```sh
mvn verify                       # compile, run the fast test-suite, build the jars
mvn verify -Pslow                # also cross-check the solver against SAT4J on the SATLIB instances
java -jar target/IP-BranchAndBound-2.0.0-SNAPSHOT-all.jar datasets/quinn.cnf
```

```
usage: java -jar IP-BranchAndBound-all.jar [options] FILE_OR_DIR...
  --bfs            width-first search (default, as in the paper)
  --dfs            depth-first search
  --max-nodes N    give up after N nodes (answer UNKNOWN)
  --no-model       do not print the satisfying assignment
  -h, --help       this message
```

Directories are searched recursively for `.cnf` files. The output follows the SAT-competition
conventions (`c` comments, one `s SATISFIABLE | UNSATISFIABLE | UNKNOWN` line, `v` model lines):

```
c datasets/quinn.cnf: 16 variables, 18 clauses
c branching order: 2 ranked by (IP1), 0 by (IP2), 14 unranked
c nodes: 5 explored, 5 relaxations solved, 0 falsified, 2 pruned, max active 2
c time: 0.047 s
s SATISFIABLE
v 1 -2 3 -4 5 6 7 8 9 -10 -11 12 13 -14 -15 16 0
```

Exit code is 10 (satisfiable), 20 (unsatisfiable) or 0 (unknown) for a single input, 0 for several inputs
and 1 for usage or I/O errors. Diagnostics are logged to **stderr** through Log4j 2; raise the level with
`-Dlog.level=debug` (branching order, integral node) or `-Dlog.level=trace` (every node).

### Datasets

`datasets/` ships the two small examples `simple.cnf` and `quinn.cnf` and two SATLIB families used in the
paper's experiments: **uf75-325** (100 uniform random 3-SAT instances, 75 variables, 325 clauses, all
satisfiable) and **flat30-60** (100 flat graph-colouring instances, 90 variables, 300 clauses, all
satisfiable).

## Using it as a library

```java
CnfFormula formula = new DimacsReader().read(Path.of("datasets/quinn.cnf"));

TuBranchAndBoundSolver solver = new TuBranchAndBoundSolver(
        TuBranchAndBoundSolver.Options.defaults()
                .withStrategy(TuBranchAndBoundSolver.SearchStrategy.DEPTH_FIRST));
SatResult result = solver.solve(formula);

if (result.isSatisfiable()) {
    boolean[] model = result.model().orElseThrow();   // model[i] is the value of x_{i+1}
}
System.out.println(result.statistics());              // nodes, LP solves, prunings, time
System.out.println(result.branchingOrder());          // the order S of Procedure 1
```

The pieces can be used on their own:

| Class | Role in the paper |
|---|---|
| `cnf.Literal`, `cnf.Clause`, `cnf.CnfFormula` | the CNF formula; `cnf.DimacsReader` parses DIMACS files |
| `tubb.Ip1Formulation` | the (IP1) matrix `A`, vector `b` and the pair degrees `eᵢⱼ` (Section III-A) |
| `tubb.Ip2Formulation` | the (IP2) literal matrix of a reduced problem and the triple degrees `eᵢⱼₖ` (Section III-B) |
| `tubb.BranchingOrder` | Procedure 1 |
| `tubb.Ip1Relaxation` | the LP relaxation of (IP1) at a node with some variables fixed |
| `tubb.TuBranchAndBoundSolver` | Algorithm 1 |
| `lp.LinearProgramSolver`, `lp.SimplexLpSolver` | LP abstraction and its simplex implementation (Apache Commons Math) |

Any LP solver that returns **vertex** solutions can be plugged in through `Options.withLpSolver`.

## Tests

`mvn verify` runs 156 unit tests (a few seconds):

* parsers and the CNF model, including malformed inputs;
* the (IP1) matrix of the paper's example, all 8 non-TU 2×2 and all 6 non-TU 3×3 patterns from
  Section III, and the equality of the triangle-based triple enumeration with brute force;
* the greedy ranking and Procedure 1 on the paper's example;
* the relaxation outcomes (falsified, positive, fractional, integral);
* the solver on satisfiable and unsatisfiable formulas, and on **random 3-SAT at the phase transition
  cross-checked against [SAT4J](https://www.sat4j.org/)** (test-scope dependency only), with both search
  strategies;
* the command line.

`mvn verify -Pslow` additionally runs the first instances of each SATLIB family against SAT4J
(`-Dbenchmark.instances=N` to change how many; the default is 3).

## Benchmarks

Indicative numbers from a single run in a cloud container (JDK 21, no warm-up), for comparison with
Table I of the paper, which reports 704 nodes on average (minimum 8, maximum 6502) for the 75×325 family:

| Instance | Search | Nodes | LP solves | Time |
|---|---|---:|---:|---:|
| `quinn.cnf` (16×18) | BFS | 5 | 5 | 0.05 s |
| `uf75-01.cnf` (75×325) | BFS (paper) | 1069 | 1066 | 44 s |
| `uf75-01.cnf` (75×325) | DFS | 289 | 289 | 14 s |
| `flat30-1.cnf` (90×300) | BFS (paper) | 23 | 19 | 2.7 s |
| `flat30-1.cnf` (90×300) | DFS | 13 | 12 | 2.1 s |

Nearly all the time is spent in the dense simplex solver (about 40 ms per relaxation on uf75); the node
counts are in line with the paper. Depth-first search finds a model much sooner on satisfiable instances,
which is why the slow test-suite uses it.

## Design notes

**LP solver.** The paper's argument is that a TU constraint matrix has integral *vertices*, so the LP must
return a vertex: a simplex method. Interior-point methods converge to the analytic centre of the optimal
face and almost never return an integral point even when one exists. The relaxation is therefore solved
with the two-phase simplex of Apache Commons Math behind a small `LinearProgramSolver` interface.

**Relaxation at a node.** Fixed variables are substituted: clauses satisfied by a fixed literal are
dropped, a clause whose literals are all fixed false makes the node infeasible without solving an LP, and
the remaining rows read `Σ_free ±xⱼ + sᵢ ≥ 1 − (#free complement literals)`. The bound `s ≤ 1` is implied by
`0 ≤ x ≤ 1` and omitted. An integral zero-valued solution is verified against the formula before being
reported.

**Ranking details not fixed by the paper.** Ties in `argmax wᵢ` are broken towards the smallest index.
When a variable is ranked, every coupling that involves it is zeroed (for (IP2) this covers both of its
literal columns, since fixing the variable removes both), which keeps `w` consistent with the reduced
matrix. The couplings are enumerated from the clauses (pairs sharing a clause, triangles of the literal
co-occurrence graph) instead of over all `n²` pairs and `(2n)³` triples; both enumerations are exact and
unit-tested against brute force.

**Input normalisation.** Duplicate literals in a clause are removed and tautological clauses are dropped,
so `A` is always a 0-±1 matrix. Clauses ending without `0` at the end of the file and the SATLIB `%`
trailer are accepted.

### What changed since the first version

The original code was reviewed against the paper and rewritten. The main defects it had:

* the LP relaxation had a single slack variable shared by all clauses instead of one per clause, and
  fixing a variable to 1 never updated the right-hand side (`RealVector.add` result discarded);
* the "`x = 1`" child wrote into the "`x = 0`" child's map, `Node.compareTo` truncated bound differences to
  `int`, and the pruning test was inverted; combined with no integrality check, every instance without
  unit clauses was reported satisfiable at the root (where `x = ½` always gives a zero relaxation);
* the (IP2) column index of `¬xᵢ` was `2i − 1`, colliding with the positive column of `x₂ᵢ`; (IP2) was built
  from the full problem rather than the reduced one, and its column index was used directly as a variable;
* `n` was the number of distinct variables actually occurring instead of the declared count, so a formula
  with an unused variable indexed out of bounds;
* the interior-point solver JOptimizer (unmaintained, pulling in Log4j 1.x and Colt) was used, see above.

Dependencies were brought to their current releases (Java 21, Log4j 2.26, JUnit 6, Maven plugins), SAT4J
is now a test-only oracle instead of a parser dependency, and a GitHub Actions workflow runs the build.

## Limitations and possible improvements

* **LP speed dominates.** Commons Math's simplex is dense and rebuilds the tableau at every node. A bounded
  dual simplex with warm starts (the child LP differs from the parent only by a variable bound) would cut
  the per-node cost by one or two orders of magnitude; wrapping a native solver such as HiGHS, GLOP
  (OR-Tools) or CLP through `LinearProgramSolver` is a drop-in alternative.
* **Logical propagation.** The paper fixes exactly one variable per level. Unit propagation and pure-literal
  elimination after each fixing are cheap, keep the relaxation smaller and would remove many LP solves;
  they do not change the method's structure.
* **Dynamic ranking.** `S` is computed once on the full matrix. Recomputing the non-TU degrees on the
  reduced matrix at each node (the paper's "recalculate `wᵢ`" taken literally) is more expensive but adapts
  the order to the sub-problem.
* **Search strategy.** Breadth-first search (the paper's choice) stores up to `2^depth` nodes. Depth-first
  search uses linear memory and finds models sooner; best-first on the LP value is another option. Both
  BFS and DFS are implemented.
* **TU detection.** When the reduced matrix is recognised as TU (e.g. a network or interval matrix) the
  sub-problem is solved by a single LP, which could terminate branches early.
* **Parallelism.** Sibling nodes are independent; the queue can be processed by a pool of workers with
  one LP solver each.
* **Scaling the degree computation.** The (IP2) triangle enumeration is exact and fast on 3-SAT, but on
  dense formulas with long clauses the number of triangles grows cubically; a sampled or bounded variant
  would be needed there.

## License

MIT, see [LICENSE](./LICENSE). The paper in `docs/` is © IEEE and included for reference.
