// Original Verity is an external dependency. This file contains only our runtime compatibility rules.
function initializeCoreMod() {
    var O = Java.type('org.objectweb.asm.Opcodes');
    var Insn = Java.type('org.objectweb.asm.tree.InsnNode');
    var Var = Java.type('org.objectweb.asm.tree.VarInsnNode');
    var Ldc = Java.type('org.objectweb.asm.tree.LdcInsnNode');
    var Call = Java.type('org.objectweb.asm.tree.MethodInsnNode');
    var List = Java.type('org.objectweb.asm.tree.InsnList');
    var Jump = Java.type('org.objectweb.asm.tree.JumpInsnNode');
    var Label = Java.type('org.objectweb.asm.tree.LabelNode');
    var Frame = Java.type('org.objectweb.asm.tree.FrameNode');
    var targets = [
        'entity.custom.VerityEntity', 'entity.custom.VerityDemonEntity', 'entity.AI.AiAPI',
        'event.DemonWindowSpawner', 'event.VerityVisuals', 'event.ModClientEvents', 'event.ModEvents',
        'item.VerityItem', 'mixin.LightTextureMixin', 'mixin.TitleScreenMixin',
        'network.PlayTtsPayload', 'client.audio.MicrophoneRecorder', 'client.audio.MicrophoneManager', 'client.KeybindRegistry'
    ];
    function replace(m, nodes) {
        m.instructions.clear(); m.tryCatchBlocks.clear();
        if (m.localVariables !== null) m.localVariables.clear();
        m.visibleLocalVariableAnnotations = null; m.invisibleLocalVariableAnnotations = null;
        for (var j = 0; j < nodes.length; j++) m.instructions.add(nodes[j]);
        m.maxStack = 8;
    }
    function noop(m) { replace(m, [new Insn(O.RETURN)]); }
    function itemGate(m, method, descriptor) {
        var gate=new List(),pass=new Label();gate.add(new Var(O.ALOAD,0));gate.add(new Call(O.INVOKESTATIC,'dev/johnmart19/veritycompanion/CompanionItemLifecycle',method,descriptor,false));gate.add(new Jump(O.IFEQ,pass));gate.add(new Insn(O.RETURN));gate.add(pass);gate.add(new Frame(O.F_SAME,0,null,0,null));m.instructions.insert(gate);
    }
    function transform(c) {
        var name = String(c.name);
        var methods = c.methods.toArray();
        for (var k = 0; k < methods.length; k++) {
            var m = methods[k], n = String(m.name);
            if (name.endsWith('/MicrophoneManager')) {
                if(n === 'scanForMicrophones') replace(m,[new Var(O.ALOAD,0),new Call(O.INVOKESTATIC,'dev/johnmart19/veritycompanion/ClientMicrophoneDevices','scan','(Ljavax/sound/sampled/AudioFormat;)V',false),new Insn(O.RETURN)]);
                if(n === 'getSelectedMicrophone') replace(m,[new Call(O.INVOKESTATIC,'dev/johnmart19/veritycompanion/ClientMicrophoneDevices','selected','()Ljavax/sound/sampled/Mixer$Info;',false),new Insn(O.ARETURN)]);
                if(n === 'cycleMicrophone') replace(m,[new Call(O.INVOKESTATIC,'dev/johnmart19/veritycompanion/ClientMicrophoneDevices','cycle','()V',false),new Insn(O.RETURN)]);
            }
            if (name.endsWith('/KeybindRegistry') && n === '<clinit>') { var keys=m.instructions.toArray(); for(var q=0;q<keys.length;q++)if(keys[q].getOpcode()===O.BIPUSH && keys[q].operand===86)keys[q].operand=82; }
            if (name.endsWith('/AiAPI')) {
                if (n === 'askGroq') replace(m, [new Var(O.ALOAD,0),new Var(O.ALOAD,1),new Call(O.INVOKESTATIC,'dev/johnmart19/veritycompanion/CompanionBrain','ask','(Lnet/minecraft/world/entity/Entity;Ljava/lang/String;)Ljava/lang/String;',false),new Insn(O.ARETURN)]);
                if (n === 'getApiKey') replace(m,[new Ldc(''),new Insn(O.ARETURN)]);
                if (n === 'playNativeTTS' || n === 'playTTS' || n.indexOf('lambda$playTTS$') === 0) noop(m);
                if (n === 'transcribeAudio') replace(m,[new Var(O.ALOAD,0),new Var(O.ALOAD,1),new Call(O.INVOKESTATIC,'dev/johnmart19/veritycompanion/ClientVoice','upload','([BLjavax/sound/sampled/AudioFormat;)Ljava/lang/String;',false),new Insn(O.ARETURN)]);
            }
            if (name.endsWith('/VerityEntity') && n === 'transformIntoDemon') noop(m);
            if (name.endsWith('/VerityEntity') && n === 'getTextureRL') replace(m,[new Var(O.ALOAD,0),new Call(O.INVOKESTATIC,'dev/johnmart19/veritycompanion/ClientExpression','texture','(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/resources/ResourceLocation;',false),new Insn(O.ARETURN)]);
            if (name.endsWith('/VerityEntity') && n === 'setVariant') {
                var force = new List();force.add(new Var(O.ALOAD,1));force.add(new Call(O.INVOKESTATIC,'dev/johnmart19/veritycompanion/EmotionPolicy','safeVariant','(Ljava/lang/String;)Ljava/lang/String;',false));force.add(new Var(O.ASTORE,1));m.instructions.insert(force);
            }
            if (name.endsWith('/VerityDemonEntity') && n === 'registerGoals') noop(m);
            if (name.endsWith('/VerityDemonEntity') && n === 'tick') replace(m,[new Var(O.ALOAD,0),new Call(O.INVOKESPECIAL,String(c.superName),'tick','()V',false),new Var(O.ALOAD,0),new Call(O.INVOKESTATIC,'dev/johnmart19/veritycompanion/RivalController','tick','(Lnet/minecraft/world/entity/Entity;)V',false),new Insn(O.RETURN)]);
            if (name.endsWith('/VerityDemonEntity') && n === 'hurt') replace(m,[new Var(O.ALOAD,0),new Var(O.ALOAD,1),new Var(O.FLOAD,2),new Call(O.INVOKESTATIC,'dev/johnmart19/veritycompanion/RivalController','hurt','(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;F)Z',false),new Insn(O.IRETURN)]);
            if (name.endsWith('/DemonWindowSpawner') && n === 'onPlayerTick') noop(m);
            if (name.endsWith('/VerityVisuals') && n.indexOf('on') === 0) noop(m);
            if (name.endsWith('/LightTextureMixin') && n === 'verity_trueDarkness') noop(m);
            if (name.endsWith('/TitleScreenMixin') && n === 'drawCustomBackground') noop(m);
            if (name.endsWith('/ModClientEvents') && (n === 'onClientTick' || n === 'onScreenInit')) noop(m);
            if (name.endsWith('/VerityItem') && n === 'onDestroyed') noop(m);
            if (name.endsWith('/ModEvents') && n === 'lambda$onPlayerInteract$2') {
                var boxInstructions=m.instructions.toArray();for(var bi=0;bi<boxInstructions.length;bi++)if(boxInstructions[bi].getOpcode()===O.ASTORE && boxInstructions[bi].var===4){var claim=new List();claim.add(new Var(O.ALOAD,4));claim.add(new Var(O.ALOAD,2));claim.add(new Var(O.ALOAD,1));claim.add(new Call(O.INVOKESTATIC,'dev/johnmart19/veritycompanion/CompanionItemLifecycle','claimBoxBody','(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;)V',false));m.instructions.insert(boxInstructions[bi],claim);break;}
            }
            if (name.endsWith('/ModEvents') && n === 'onDespawn') replace(m,[new Var(O.ALOAD,0),new Call(O.INVOKESTATIC,'dev/johnmart19/veritycompanion/CompanionItemLifecycle','expired','(Lnet/neoforged/neoforge/event/entity/item/ItemExpireEvent;)V',false),new Insn(O.RETURN)]);
            if (name.endsWith('/ModEvents') && n === 'onPlayerInteract') itemGate(m,'interact','(Lnet/neoforged/neoforge/event/entity/player/PlayerInteractEvent$EntityInteract;)Z');
            if (name.endsWith('/ModEvents') && n === 'onPlayerBlockInteract') itemGate(m,'place','(Lnet/neoforged/neoforge/event/entity/player/PlayerInteractEvent$RightClickBlock;)Z');
            if (name.endsWith('/ModEvents') && (n === 'onDeath' || n === 'onPlayerChat' || n === 'onPlayerFirstJoin' || /^lambda\$onPlayerInteract\$[345]$/.test(n))) noop(m);
            if (name.endsWith('/PlayTtsPayload') && (n === 'handleData' || n === 'lambda$handleData$0')) noop(m);
            if (name.endsWith('/MicrophoneRecorder') && n === 'startRecording') { var begin = new List();begin.add(new Var(O.ALOAD,0));begin.add(new Call(O.INVOKESTATIC,'dev/johnmart19/veritycompanion/ClientVoice','beginRecording','(Ljava/lang/Object;)V',false));m.instructions.insert(begin); }
            var instructions = m.instructions.toArray();
            for (var j = 0; j < instructions.length; j++) {
                var i = instructions[j];
                if (name.endsWith('/VerityEntity') && i.name === 'getDayTime' && i.desc === '()J') {
                    var r = new List();r.add(new Insn(O.POP));r.add(new Insn(O.LCONST_0));m.instructions.insertBefore(i,r);m.instructions.remove(i);
                }
                if (name.endsWith('/ModEvents') && n === 'entitySpawnEvent' && (i.name === 'kill' || i.name === 'discard') && i.desc === '()V') m.instructions.set(i,new Insn(O.POP));
            }
        }
        print('[Verity Companion] compatibility transformed '+name);
        return c;
    }
    var result = {};
    for (var t = 0; t < targets.length; t++) {
        var cls = 'varmite.verity.'+targets[t];
        result['friendly_'+t] = {target:{type:'CLASS',name:cls}, transformer:transform};
    }
    return result;
}
