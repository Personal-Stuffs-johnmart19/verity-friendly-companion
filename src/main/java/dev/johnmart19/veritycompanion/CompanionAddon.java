package dev.johnmart19.veritycompanion;

import net.neoforged.fml.common.Mod;

import net.neoforged.bus.api.IEventBus;

import net.neoforged.neoforge.common.NeoForge;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import net.neoforged.neoforge.event.ServerChatEvent;

import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import net.minecraft.server.level.ServerPlayer;

import net.minecraft.network.chat.Component;

import net.minecraft.ChatFormatting;



@Mod("verity_companion")

public final class CompanionAddon {

 public CompanionAddon(IEventBus bus){HorrorController.register();CompanionFollow.register();WorldEvents.register();ThreatEvents.register();DeathEvents.register();NeoForge.EVENT_BUS.addListener(CompanionAddon::started);NeoForge.EVENT_BUS.addListener(CompanionAddon::catalogTick);NeoForge.EVENT_BUS.addListener(CompanionAddon::datapackSync);NeoForge.EVENT_BUS.addListener(AdvancementTools::onEarn);NeoForge.EVENT_BUS.addListener(AdvancementTools::onLogout);NeoForge.EVENT_BUS.addListener(EffectTools::onLogout);bus.addListener(CompanionAddon::register);NeoForge.EVENT_BUS.addListener(CompanionAddon::chat);NeoForge.EVENT_BUS.addListener(CompanionAddon::login);NeoForge.EVENT_BUS.addListener(CompanionAddon::logout);NeoForge.EVENT_BUS.addListener(CompanionAddon::stopped);NeoForge.EVENT_BUS.addListener(CompanionCommands::register);if(net.neoforged.fml.loading.FMLEnvironment.dist==net.neoforged.api.distmarker.Dist.CLIENT){ClientVoice.registerEvents();ClientHorror.register();}}

 static void register(RegisterPayloadHandlersEvent event){var r=event.registrar("7");r.playToClient(HorrorVisualState.TYPE,HorrorVisualState.CODEC,(p,c)->ClientHorror.accept(p));r.playToClient(VoiceSettings.TYPE,VoiceSettings.CODEC,(p,c)->c.enqueueWork(()->ClientVoice.setTtsEnabled(p.enabled())));r.playToClient(VoiceExpression.TYPE,VoiceExpression.CODEC,(p,c)->c.enqueueWork(()->{ClientVoice.checkConnection();ClientExpression.prepare(p);}));r.playToServer(AudioPacket.MIC,AudioPacket.codec(false),ServerVoice::receive);r.playToServer(VoiceControl.TYPE,VoiceControl.CODEC,ServerVoice::interrupt);r.playToClient(AudioPacket.VOICE,AudioPacket.codec(true),(p,c)->ClientVoice.receive(p,c));}

 static void chat(ServerChatEvent event){CompanionBrain.init();String text=event.getMessage().getString();String low=text.toLowerCase(java.util.Locale.ROOT);if(CompanionBrain.settings.listenAllChat||low.contains("verity")||low.contains("верити")||low.contains(Relations.name(event.getPlayer()).toLowerCase(java.util.Locale.ROOT))){event.setCanceled(true);ServerVoice.request(event.getPlayer(),text,null);}}

 static void login(PlayerEvent.PlayerLoggedInEvent event){if(event.getEntity() instanceof ServerPlayer p){CompanionBrain.init();Relations.refresh(p);net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,new VoiceSettings(Relations.get(p).tts));p.sendSystemMessage(Component.literal(Relations.tr(p,"Verity Companion: пиши в чате или удерживай R и говори. /companion mood auto|on|off — шкала, /companion rename <имя> — имя. Ключи и обработка голоса только на сервере.","Verity Companion: type in chat or hold R to speak. /companion mood auto|on|off — mood bar, /companion rename <name> — name. Keys and voice processing stay on the server.")).withStyle(ChatFormatting.AQUA));}}

 static void logout(PlayerEvent.PlayerLoggedOutEvent event){if(event.getEntity() instanceof ServerPlayer p)HorrorController.logout(p);ServerVoice.disconnect(event.getEntity().getUUID());RivalController.remove(event.getEntity().getUUID());Relations.logout(event.getEntity().getUUID());ConversationIntent.clear(event.getEntity().getUUID());WorldEvents.clear(event.getEntity().getUUID());ThreatEvents.clear(event.getEntity().getUUID());DeathEvents.clear(event.getEntity().getUUID());EffectIntent.clear(event.getEntity().getUUID());BuildConversation.clear(event.getEntity().getUUID());}

 static void started(ServerStartedEvent event){SpeechSession.start(event.getServer());GameCatalogue.start(event.getServer());NativeStructureIndex.start(event.getServer());}

 static void catalogTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event){GameCatalogue.tick(event.getServer());}
 static void datapackSync(net.neoforged.neoforge.event.OnDatapackSyncEvent event){if(event.getPlayer()==null){GameCatalogue.start(event.getPlayerList().getServer());NativeStructureIndex.start(event.getPlayerList().getServer());SchematicLibrary.clear();}}
 static void stopped(ServerStoppedEvent event){HorrorController.clear();SpeechSession.stop();CompanionFollow.clear();ServerVoice.clear();GameCatalogue.clear();NativeStructureIndex.clear();SchematicLibrary.clear();CompanionBrain.clearRuntime();RivalController.clear();Relations.clear();}

}
