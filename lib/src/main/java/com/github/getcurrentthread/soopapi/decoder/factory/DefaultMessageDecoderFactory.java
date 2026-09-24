package com.github.getcurrentthread.soopapi.decoder.factory;

import java.util.Map;

import com.github.getcurrentthread.soopapi.decoder.message.*;
import com.github.getcurrentthread.soopapi.event.ChatEvent;

/**
 * 서비스 코드가 있는 서버 이벤트마다 디코더를 등록한 기본 팩토리입니다.
 *
 * <p>{@link ChatEvent#NONE_TYPE}에는 디코더를 두지 않습니다. 모르는 서비스 코드의 패킷은 {@link
 * com.github.getcurrentthread.soopapi.decoder.MessageDispatcher}가 원래 코드를 담은 {@link
 * com.github.getcurrentthread.soopapi.event.model.UnknownEvent}로 전달합니다.
 */
public class DefaultMessageDecoderFactory implements MessageDecoderFactory {
    private static final Map<ChatEvent, IMessageDecoder> DECODERS =
            Map.<ChatEvent, IMessageDecoder>ofEntries(
                    Map.entry(ChatEvent.CHAT_MESSAGE, new ChatMessageDecoder()),
                    Map.entry(ChatEvent.SEND_BALLOON, new SendBalloonDecoder()),
                    Map.entry(ChatEvent.OGQ_EMOTICON, new OGQEmoticonDecoder()),
                    Map.entry(ChatEvent.OGQ_EMOTICON_GIFT, new GiftOGQEmoticonDecoder()),
                    Map.entry(ChatEvent.MANAGER_CHAT, new ManagerChatDecoder()),
                    Map.entry(ChatEvent.CHOCOLATE, new ChocolateDecoder()),
                    Map.entry(
                            ChatEvent.CHOCOLATE_SUB, new ChocolateDecoder(ChatEvent.CHOCOLATE_SUB)),
                    Map.entry(ChatEvent.SEND_QUICK_VIEW, new QuickViewDecoder()),
                    Map.entry(ChatEvent.GIFT_TICKET, new GiftTicketDecoder()),
                    Map.entry(ChatEvent.VOD_BALLOON, new VODBalloonDecoder()),
                    Map.entry(ChatEvent.ADCON_EFFECT, new AdconEffectDecoder()),
                    Map.entry(ChatEvent.VIDEO_BALLOON, new VideoBalloonDecoder()),
                    Map.entry(ChatEvent.SEND_SUBSCRIPTION, new GiftSubscriptionDecoder()),
                    Map.entry(ChatEvent.ITEM_DROPS, new ItemDropsDecoder()),
                    Map.entry(ChatEvent.GEM_ITEM_SEND, new GemItemDecoder()),
                    Map.entry(ChatEvent.LIVE_CAPTION, new LiveCaptionDecoder()),
                    Map.entry(ChatEvent.SLOW_MODE, new SlowModeDecoder()),
                    Map.entry(ChatEvent.SET_ADMIN_FLAG, new SetAdminFlagDecoder()),
                    Map.entry(ChatEvent.TOP_FAN, new TopFanDecoder()),
                    Map.entry(ChatEvent.TOP_FAN_SUB, new TopFanSubDecoder()),
                    Map.entry(ChatEvent.SUPER_CHAT, new SuperChatDecoder()),
                    Map.entry(ChatEvent.STAR_COIN, new StarCoinDecoder()),
                    Map.entry(ChatEvent.ADMIN_CHAT_USER, new AdminChatUserDecoder()),
                    Map.entry(ChatEvent.ITEM_STATUS, new ItemStatusDecoder()),
                    Map.entry(ChatEvent.ITEM_USING, new ItemUsingDecoder()),
                    Map.entry(ChatEvent.USE_QUICK_VIEW, new UseQuickViewDecoder()),
                    Map.entry(ChatEvent.NOTIFY_POLL, new NotifyPollDecoder()),
                    Map.entry(ChatEvent.CHAT_BLOCK_MODE, new ChatBlockModeDecoder()),
                    Map.entry(ChatEvent.SET_BROAD_INFO, new SetBroadInfoDecoder()),
                    Map.entry(ChatEvent.BUY_GOODS, new BuyGoodsDecoder()),
                    Map.entry(ChatEvent.BUY_GOODS_SUB, new BuyGoodsSubDecoder()),
                    Map.entry(ChatEvent.SEND_PROMOTION, new SendPromotionDecoder()),
                    Map.entry(ChatEvent.NOTIFY_VR, new NotifyVRDecoder()),
                    Map.entry(ChatEvent.NOTIFY_MOBBROAD_PAUSE, new NotifyMobBroadPauseDecoder()),
                    Map.entry(ChatEvent.FOLLOW_ITEM, new FollowItemDecoder()),
                    Map.entry(ChatEvent.FOLLOW_ITEM_EFFECT, new FollowItemEffectDecoder()),
                    Map.entry(ChatEvent.BJ_NOTICE, new BJNoticeDecoder()),
                    Map.entry(ChatEvent.VIDEO_BALLOON_LINK, new VideoBalloonLinkDecoder()),
                    Map.entry(ChatEvent.AD_IN_BROAD_JSON, new AdInBroadJsonDecoder()),
                    Map.entry(ChatEvent.MISSION, new MissionDecoder()),
                    Map.entry(ChatEvent.MISSION_SETTLE, new MissionSettleDecoder()),
                    Map.entry(ChatEvent.CHUSER_EXTEND, new ChuserExtendDecoder()),
                    Map.entry(ChatEvent.ADMIN_CHUSER_EXTEND, new AdminChuserExtendDecoder()),
                    Map.entry(ChatEvent.JOIN_CHANNEL, new JoinChannelDecoder()),
                    Map.entry(ChatEvent.QUIT_CHANNEL, new QuitChannelDecoder()),
                    Map.entry(ChatEvent.CHAT_USER, new ChatUserDecoder()),
                    Map.entry(ChatEvent.DIRECT_CHAT, new DirectChatDecoder()),
                    Map.entry(ChatEvent.NOTICE, new NoticeDecoder()),
                    Map.entry(ChatEvent.KICK, new KickDecoder()),
                    Map.entry(ChatEvent.SET_USER_FLAG, new SetUserFlagDecoder()),
                    Map.entry(ChatEvent.SET_SUB_BJ, new SetSubBjDecoder()),
                    Map.entry(ChatEvent.SET_NICKNAME, new SetNicknameDecoder()),
                    Map.entry(ChatEvent.SERVER_STAT, new ServerStatDecoder()),
                    Map.entry(ChatEvent.NULL_16, new Null16Decoder()),
                    Map.entry(ChatEvent.ICE_MODE, new IceModeDecoder()),
                    Map.entry(ChatEvent.SEND_FAN_LETTER, new SendFanLetterDecoder()),
                    Map.entry(ChatEvent.ICE_MODE_EX, new IceModeExDecoder()),
                    Map.entry(ChatEvent.GET_ICE_MODE_RELAY, new GetIceModeRelayDecoder()),
                    Map.entry(ChatEvent.RELOAD_BURN_LEVEL, new ReloadBurnLevelDecoder()),
                    Map.entry(ChatEvent.BLIND_KICK, new BlindKickDecoder()),
                    Map.entry(ChatEvent.APPEND_DATA, new AppendDataDecoder()),
                    Map.entry(ChatEvent.BASEBALL_EVENT, new BaseballEventDecoder()),
                    Map.entry(ChatEvent.PAID_ITEM, new PaidItemDecoder()),
                    Map.entry(ChatEvent.SNS_MESSAGE, new SnsMessageDecoder()),
                    Map.entry(ChatEvent.SNS_MODE, new SnsModeDecoder()),
                    Map.entry(ChatEvent.SEND_BALLOON_SUB, new SendBalloonSubDecoder()),
                    Map.entry(ChatEvent.SEND_FAN_LETTER_SUB, new SendFanLetterSubDecoder()),
                    Map.entry(ChatEvent.SEND_ADMIN_NOTICE, new SendAdminNoticeDecoder()),
                    Map.entry(ChatEvent.BAN_WORD, new BanWordDecoder()),
                    Map.entry(ChatEvent.FREECAT_OWNER_JOIN, new FreecatOwnerJoinDecoder()),
                    Map.entry(ChatEvent.KICK_AND_CANCEL, new KickAndCancelDecoder()),
                    Map.entry(ChatEvent.KICK_USERLIST, new KickUserListDecoder()),
                    Map.entry(ChatEvent.CLI_DOBAE_INFO, new CliDobaeInfoDecoder()),
                    Map.entry(ChatEvent.KICK_MSG_STATE, new KickMsgStateDecoder()),
                    Map.entry(ChatEvent.TRANSLATION, new TranslationDecoder()),
                    Map.entry(ChatEvent.KEEP_ALIVE, new KeepAliveDecoder()),
                    Map.entry(ChatEvent.LOGIN, new LoginDecoder()),
                    Map.entry(ChatEvent.SET_CHANNEL_NAME, new SetChannelNameDecoder()),
                    Map.entry(ChatEvent.BJ_STICKER_ITEM, new BJStickerItemDecoder()),
                    Map.entry(ChatEvent.STATION_ADCON, new StationAdconDecoder()),
                    Map.entry(ChatEvent.EMOTICON_TICKET, new EmoticonTicketDecoder()),
                    Map.entry(ChatEvent.TRANSLATION_STATE, new TranslationStateDecoder()),
                    Map.entry(ChatEvent.SET_DUMB, new SetDumbDecoder()),
                    Map.entry(ChatEvent.SET_BJ_STAT, new SetBjStatDecoder()),
                    Map.entry(ChatEvent.CLUB_COLOR, new ClubColorDecoder()),
                    Map.entry(ChatEvent.BDM_ADD_BLACK_INFO, new BdmAddBlackInfoDecoder()),
                    Map.entry(ChatEvent.NOTI_GAME_RANKER, new NotiGameRankerDecoder()),
                    Map.entry(ChatEvent.ITEM_SELL_EFFECT, new ItemSellEffectDecoder()),
                    Map.entry(ChatEvent.UPDATE_TICKET, new UpdateTicketDecoder()),
                    Map.entry(ChatEvent.TOP_CLAN, new TopClanDecoder()),
                    Map.entry(ChatEvent.TOP_CLAN_SUB, new TopClanSubDecoder()),
                    Map.entry(ChatEvent.VOD_ADCON, new VodAdconDecoder()));

    @Override
    public Map<ChatEvent, IMessageDecoder> createDecoders() {
        return DECODERS;
    }
}
