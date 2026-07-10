# Milestone 1 — converter over LB's REAL mixin set (scale, live in a bare MC)
ConvScaleAgent: bootstraps the real Mixin engine with LB's real configs (liquidbounce.mixins.json +
liquidbounce-fabric.mixins.json), then for each of LB's 151 @Mixin target classes runs the real
transformClassBytes -> feeds the schema-changing X through RetransformConverter -> verifies target'
is schema-identical to O (fields/methods/interfaces) and both parse.

[17:36:54] [Attach Listener/INFO]: [STDOUT]: [SCALE] targets=151 transformed=146 CONVERTED-CLEAN=146 no-transform=0 FAILED=5

146/146 transformable real LB mixin targets CONVERT CLEAN. 0 real edge cases.
The 5 'FAILED' are 'no class bytes' = sodium/lithium (caffeinemc) mod-compat targets absent from bare
vanilla MC (only apply when the mod is installed) -- not a converter failure.

Note: schema-legality != runtime correctness for the 5 hard @Local -- they convert schema-legally here
(so they pass this scale test) but their @Local-writeback/ordinal semantics are deferred to the on-load
path for runtime correctness (documented in PROGRESS.md).
