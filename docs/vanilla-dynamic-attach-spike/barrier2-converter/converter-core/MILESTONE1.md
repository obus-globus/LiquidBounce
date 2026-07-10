# Converter core — Milestone 1 (mechanical core, verified live)
## Offline schema-legality (TestConverter): O + (added field + added method + added interface + body-edit)
  dropped interface, relocated field+method; target' schema-IDENTICAL to O (fields/methods/interfaces); both verify.
## LIVE on real Mixin-engine output (ConvAgent + MixinMinecraftConv, bare fully-loaded MC):
[CONVAUTO] agentmain; retransformSupported=true
[CONVAUTO] target loaded=true
[CONVAUTO] Mixin engine: O=148439b -> X=149823b (added members present)
[CONVAUTO] converter: droppedIfaces=[] relocFields=[convTickCount I] relocMethods=[handler$zza000$convHook (Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V]
[CONVAUTO] defined sidecar + state into target loader
[CONVAUTO] retransformClasses(ALREADY-LOADED Minecraft) with AUTO-CONVERTED bytes: SUCCESS
[CONVAUTO][LIVE] auto-converted mixin fired convTickCount=100 (Mixin-engine output -> RetransformConverter -> retransformed onto ALREADY-LOADED Minecraft)
[CONVAUTO][LIVE] auto-converted mixin fired convTickCount=200 (Mixin-engine output -> RetransformConverter -> retransformed onto ALREADY-LOADED Minecraft)
[CONVAUTO][LIVE] auto-converted mixin fired convTickCount=300 (Mixin-engine output -> RetransformConverter -> retransformed onto ALREADY-LOADED Minecraft)
[CONVAUTO][LIVE] auto-converted mixin fired convTickCount=400 (Mixin-engine output -> RetransformConverter -> retransformed onto ALREADY-LOADED Minecraft)
