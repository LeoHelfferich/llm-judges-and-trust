# Dataset attribution

The checked-in file `items.json` is a 50-question subset of the SQuAD 1.1 development set, with deterministic answer variants produced by `VariantRules` in this repository.

- **Dataset:** SQuAD 1.1 (Stanford Question Answering Dataset), development split
- **License:** [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)
- **Source file:** https://rajpurkar.github.io/SQuAD-explorer/dataset/dev-v1.1.json
- **Paper:** Rajpurkar, P., Zhang, J., Lopyrev, K., and Liang, P. (2016). SQuAD: 100,000+ Questions for Machine Comprehension of Text. In *Proceedings of EMNLP*. https://aclanthology.org/D16-1264/
- **Passages:** Wikipedia. Wikipedia text is itself CC BY-SA. Article titles are stored on each item.

The subset is a derivative of SQuAD and stays under CC BY-SA 4.0. The code in this repository is MIT (see `LICENSE`) and that license does not apply to `items.json`. Regenerating it:

```bash
curl -fsSL -o /tmp/dev-v1.1.json https://rajpurkar.github.io/SQuAD-explorer/dataset/dev-v1.1.json
./gradlew -q run --args="prepare --squad /tmp/dev-v1.1.json --out data/items.json"
```

Selection rules are described by `VariantRules.SELECTION_NOTE` and in the README. No private or company data is included.
