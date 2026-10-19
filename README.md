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

On top of the published method this implementation adds, all optional: a warm-started revised dual
simplex for the relaxations, unit propagation and pure-literal elimination at every node, clause learning
from conflicts, per-node branching rules (dynamic non-TU ranking of the reduced problem, Jeroslow–Wang),
depth-first search and parallel node evaluation with work stealing. `--paper` runs the method exactly as
published.

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
  --bfs              width-first search (default, as in the paper)
  --dfs              depth-first search
  --rule static|dynamic|jw   branching rule: paper's static order (default), per-node non-TU
                     ranking, or Jeroslow-Wang
  --dynamic          same as --rule dynamic
  --no-propagation   disable unit propagation and pure-literal elimination (and learning)
  --no-learning      disable clause learning
  --lp dual|tableau|simplex  revised dual simplex (default), tableau dual simplex, or rebuilt Commons Math simplex
  --threads N        number of worker threads (default 1)
  --paper            the method exactly as published: --bfs, no propagation or learning, static order, 1 thread
  --max-nodes N      give up after N nodes (answer UNKNOWN)
  --no-model         do not print the satisfying assignment
  -h, --help         this message
```

Directories are searched recursively for `.cnf` files. The output follows the SAT-competition
conventions (`c` comments, one `s SATISFIABLE | UNSATISFIABLE | UNKNOWN` line, `v` model lines):

```
c datasets/quinn.cnf: 16 variables, 18 clauses
c branching order: 2 ranked by (IP1), 0 by (IP2), 14 unranked
c nodes: 3 explored, 3 relaxations solved, 0 falsified, 0 pruned, max active 2
c propagation: 12 variables fixed, 0 clauses learnt
c time: 0.031 s
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
        TuBranchAndBoundSolver.Options.defaults()   // revised dual simplex, BFS, static order, propagation, learning
                .withStrategy(TuBranchAndBoundSolver.SearchStrategy.DEPTH_FIRST)
                .withBranchingRule(TuBranchAndBoundSolver.BranchingRule.JEROSLOW_WANG)
                .withLearning(false)
                .withThreads(4));
SatResult result = solver.solve(formula);
// TuBranchAndBoundSolver.Options.paper() is the method exactly as published.

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
| `tubb.Ip1Relaxation` | classifies a node from its relaxation: falsified, positive, fractional or integral |
| `tubb.RelaxationSolver`, `tubb.RevisedDualSimplexRelaxationSolver` | the relaxation of (IP1) at a node; warm-started bounded dual simplex keeping only `B⁻¹` |
| `tubb.DualSimplexRelaxationSolver` | the same method on a full dense tableau |
| `tubb.RebuildingRelaxationSolver` | the same relaxation rebuilt at every node for a general `lp.LinearProgramSolver` |
| `tubb.Propagation` | unit propagation, pure-literal elimination (with a trail) and the reduced formula of a node |
| `tubb.ConflictAnalyzer`, `tubb.LearntClauses` | first-UIP conflict analysis, no-goods, and the shared learnt-clause database |
| `tubb.NodeRanking` | per-node branching rules: dynamic non-TU degree, Jeroslow–Wang |
| `tubb.TuBranchAndBoundSolver` | Algorithm 1, with the extensions as `Options` |
| `lp.LinearProgramSolver`, `lp.SimplexLpSolver` | LP abstraction and its simplex implementation (Apache Commons Math) |

Any LP solver that returns **vertex** solutions can be plugged in through `Options.withLpSolver`; a
stateful, warm-started one through `Options.withRelaxationSolvers`.

## Tests

`mvn verify` runs 697 unit tests (about fifteen seconds):

* parsers and the CNF model, including malformed inputs;
* the (IP1) matrix of the paper's example, all 8 non-TU 2×2 and all 6 non-TU 3×3 patterns from
  Section III, and the equality of the triangle-based triple enumeration with brute force;
* the greedy ranking and Procedure 1 on the paper's example;
* the relaxation outcomes (falsified, positive, fractional, integral) with both LP back-ends;
* both dual simplex implementations against the Commons Math simplex on hundreds of random nodes,
  including across refactorings, and the cost of warm starts;
* propagation (unit chains, conflicts, pure literals, the trail, satisfiability preservation on random
  instances), conflict analysis (textbook first-UIP cases, level-0 dropping, no-goods) and the learnt
  clause database;
* the per-node branching rules and the bounded triangle enumeration;
* the solver on satisfiable and unsatisfiable formulas, and on **random 3-SAT at the phase transition
  cross-checked against [SAT4J](https://www.sat4j.org/)** (test-scope dependency only), for sixteen
  configurations covering every option (paper, BFS/DFS, the three branching rules, propagation and
  learning on/off, 1–4 threads, the three LP back-ends);
* the command line.

`mvn verify -Pslow` additionally runs the first instances of each SATLIB family against SAT4J in three
configurations (`-Dbenchmark.instances=N` to change how many; the default is 5).

## Benchmarks

Averages over 20 instances per family from a single run in a cloud container (JDK 21, 4 cores, no JVM
warm-up). uf75 and flat30 are the SATLIB families in `datasets/` (all satisfiable); uf50 are 20 generated
uniform random 3-SAT instances with 50 variables and 218 clauses (11 satisfiable, 9 unsatisfiable). All
seven configurations returned the same answer on every instance.

| Configuration | uf75 (75×325) nodes / s | flat30 (90×300) nodes / s | uf50 sat nodes / s | uf50 unsat nodes / s |
|---|---:|---:|---:|---:|
| `--paper` (BFS, static, no propagation) | 1169 / 9.51 | 914 / 0.66 | 192 / 0.57 | 231 / 0.62 |
| default (BFS, static, propagation) | 977 / 3.34 | 56 / 0.17 | 153 / 0.30 | 221 / 0.36 |
| `--dfs` | 894 / 1.33 | 44 / 0.10 | 88 / 0.22 | 221 / 0.31 |
| `--dfs --dynamic` | 685 / 1.46 | 81 / 0.21 | 122 / 0.29 | 238 / 0.43 |
| `--dynamic` (BFS) | 940 / 3.53 | 38 / 0.22 | 153 / 0.44 | 238 / 0.51 |
| `--threads 4` (BFS) | 893 / 1.63 | 37 / 0.25 | 141 / 0.32 | 221 / 0.37 |
| `--dfs --threads 4` | 622 / 0.87 | 35 / 0.23 | 98 / 0.29 | 221 / 0.35 |

For reference, `--paper --lp simplex` (the Commons Math simplex rebuilt at every node) takes 43 s on
uf75-01 for the same 1069 nodes that the dual simplex solves in 10 s. The paper reports 704 nodes on
average (minimum 8, maximum 6502) for the 75×325 family with CPLEX; the node counts of `--paper` are of the
same order.

What the numbers say:

* **The dual simplex** cuts the per-node cost by about 4× against the rebuilt Commons Math LP, with
  identical node counts (the relaxation is the same LP).
* **Propagation** is the largest single win: 3× on uf75 and 16× on flat30 for BFS, because most infeasible
  nodes are now detected by unit propagation instead of an LP, and the remaining relaxations are smaller.
* **Depth-first search** reaches a model sooner on satisfiable instances (uf75: 2.5× faster than BFS) and
  costs nothing on unsatisfiable ones, where the whole tree is explored either way.
* **Dynamic ranking** reduces the node count on uf75 (685 vs 894 with DFS) but the per-node ranking cost
  eats the gain, and on the smaller families it explores *more* nodes. It is exact and available, but off
  by default.
* **Threads** help where the tree is large (uf75: 1.5–2× with 4 workers); on the small instances the
  scheduling overhead dominates. With a shared deque, parallel DFS loses some of its "finish one subtree
  first" behaviour, so it explores a different set of nodes than single-threaded DFS.

Rerun the matrix with the executable jar, e.g. `java -jar target/IP-BranchAndBound-*-all.jar --dfs --threads 4 datasets/UF75.325.100`.

## Design notes

**LP solver.** The paper's argument is that a TU constraint matrix has integral *vertices*, so the LP must
return a vertex: a simplex method. Interior-point methods converge to the analytic centre of the optimal
face and almost never return an integral point even when one exists. Two simplex back-ends are provided
behind the `RelaxationSolver` interface.

**Warm-started dual simplex** (`RevisedDualSimplexRelaxationSolver`, the default). All nodes share the
same rows; they differ only in the bounds of the `x` variables (`[0,1]`, `{0}` or `{1}`), so the LP is kept
in the equality form `A x + s − r = 1 − b`. Dual feasibility of a basis does not depend on the bounds, so
the optimal basis of the previous node is dual feasible for the next one once every non-basic variable is
placed at the bound matching the sign of its reduced cost; a few dual simplex pivots restore primal
feasibility, instead of the hundreds a cold start needs. The all-slack basis is dual feasible too, so no
phase one is ever required. The revised form keeps only the basis inverse `B⁻¹` (`m × m`) and the sparse
columns of `A`: a pivot is a rank-one update of `B⁻¹` in `O(m²)`, the pivot row costs `O(nnz(A))`, and the
inverse is recomputed from the original data every 500 pivots. The older `DualSimplexRelaxationSolver`
(`--lp tableau`) does the same on a full `m × (n + 2m)` tableau; `--lp simplex` rebuilds the LP at every
node for the Commons Math simplex. Both dual simplex solvers restart from the slack basis, then fall back to
Commons Math, if they ever fail to converge.

**Propagation** (`Propagation`, on by default, `--no-propagation` to disable). After each branching decision
unit propagation and pure-literal elimination are run to a fixed point. Both preserve satisfiability of the
sub-problem, so the method stays exact; they detect most infeasible nodes without any LP and shrink the
relaxations of the others. The propagation records a trail (variable, forcing clause) for conflict analysis.

**Learning** (`ConflictAnalyzer`, `LearntClauses`; on by default, `--no-learning` to disable). Every fixed
variable carries a decision level and a reason: the branching decision of a node opens a level,
unit-propagated variables share the latest level and point to their clause, pure-literal fixings are
choices and open levels of their own. When propagation falsifies a clause, first-UIP resolution along the
trail yields an asserting clause implied by the formula; when the relaxation proves a node infeasible, the
negation of its decisions is an implied no-good. Learnt clauses (at most 12 literals, 20 000 clauses) go to
a shared database and take part in the propagation of every later node, in every subtree and on every
worker. They are not added to the LP, whose rows stay fixed for the warm start.

**Branching rules** (`--rule`). `static` is the paper's order `S`. `dynamic` takes, at each node, the
variable with the largest non-TU degree of the reduced formula (pair degrees of (IP1), then triple degrees
of (IP2)), read directly off the active clauses without the greedy loop of Procedure 1, since only the top
variable is needed; ties and the all-zero case use the two-sided Jeroslow–Wang score
`J(v) = Σ_{active C ∋ v} 2^{−|C|}`. `jw` is that classic SAT heuristic alone, for comparison.

**Parallel search** (`--threads N`). With breadth-first search the workers share one FIFO queue. With
depth-first search every worker owns a stack and, when it runs dry, steals the *shallowest* node of
another worker (the largest remaining subtree), so each worker mostly finishes its own subtree. Every
worker owns a relaxation solver, so no LP state is shared; the learnt-clause database is shared.
Termination is detected with an in-flight counter; the first worker to find an integral relaxation stops
the others. Node counts vary between runs.

**Why no TU detection.** Recognising that the reduced matrix of a node is TU would tell us that its
relaxation has an integral optimal vertex. But the method already solves that relaxation at every node:
if the matrix is TU the simplex returns an integral vertex and the node is closed (satisfiable or pruned)
anyway. A TU test could only skip an LP that is solved regardless, so it was not implemented.

**Bounded triangle enumeration.** The (IP2) degrees need the triangles of the literal co-occurrence graph,
whose number grows cubically on dense formulas with long clauses. `Options.maxTriangles` (default two
million) caps the enumeration; when the cap is hit the degrees are computed from the triangles examined so
far and a warning is logged.

**Relaxation at a node.** Clauses satisfied by a fixed literal drop out, a clause whose literals are all
fixed false makes the node infeasible without an LP, and the remaining rows read
`Σ_free ±xⱼ + sᵢ ≥ 1 − (#free complement literals)`. The bound `s ≤ 1` is implied by `0 ≤ x ≤ 1` and
omitted. An integral zero-valued solution is verified against the formula before being reported.

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

* **Basis factorisation.** `B⁻¹` is kept dense, so memory is `O(m²)` and a pivot `O(m²)`. For instances
  with thousands of clauses a sparse LU factorisation of the basis with Forrest–Tomlin updates (what
  production simplex codes do), or a native solver such as HiGHS behind `RelaxationSolver`, would be the
  next step.
* **Learnt clauses in the relaxation.** Learnt clauses are only used for propagation. Adding them as rows
  of the LP would tighten the bound, at the price of a growing LP; a cutting-plane style separation of the
  most violated learnt clauses is the natural way to do it.
* **Learning heuristics.** The database is bounded by length and size only; activity-based deletion,
  clause minimisation and restarts (the standard CDCL machinery) are absent.
* **Scaling the degree computation.** The bounded enumeration keeps the (IP2) ranking affordable but gives
  approximate degrees on dense formulas; an incremental maintenance of the couplings along the search tree
  would make the dynamic rule exact and cheaper.
* **Load balancing.** Work stealing takes the shallowest node of another worker; with very unbalanced trees
  a worker can still idle while another holds a single deep stack. Splitting a stolen node's subtree, or
  stealing several nodes at once, would help.

## License

MIT, see [LICENSE](./LICENSE). The paper in `docs/` is © IEEE and included for reference.
