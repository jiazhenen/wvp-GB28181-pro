package com.genersoft.iot.vmp.storager.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.genersoft.iot.vmp.common.StreamInfo;
import com.genersoft.iot.vmp.common.SystemAllInfo;
import com.genersoft.iot.vmp.common.VideoManagerConstants;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.*;
import com.genersoft.iot.vmp.media.zlm.dto.hook.OnStreamChangedHookParam;
import com.genersoft.iot.vmp.media.zlm.dto.MediaServerItem;
import com.genersoft.iot.vmp.media.zlm.dto.StreamAuthorityInfo;
import com.genersoft.iot.vmp.service.bean.GPSMsgInfo;
import com.genersoft.iot.vmp.service.bean.MessageForPushChannel;
import com.genersoft.iot.vmp.service.bean.ThirdPartyGB;
import com.genersoft.iot.vmp.storager.IRedisCatchStorage;
import com.genersoft.iot.vmp.storager.dao.DeviceChannelMapper;
import com.genersoft.iot.vmp.storager.dao.dto.PlatformRegisterInfo;
import com.genersoft.iot.vmp.utils.DateUtil;
import com.genersoft.iot.vmp.utils.JsonUtil;
import com.genersoft.iot.vmp.utils.SystemInfoUtils;
import com.genersoft.iot.vmp.utils.redis.RedisUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@SuppressWarnings("rawtypes")
@Component
public class RedisCatchStorageImpl implements IRedisCatchStorage {

    private final Logger logger = LoggerFactory.getLogger(RedisCatchStorageImpl.class);

    @Autowired
    private DeviceChannelMapper deviceChannelMapper;

    @Autowired
    private UserSetting userSetting;

    @Override
    public Long getCSEQ() {
        String key = VideoManagerConstants.SIP_CSEQ_PREFIX  + userSetting.getServerId();

        long result =  RedisUtil.incr(key, 1L);
        if (result > Integer.MAX_VALUE) {
            RedisUtil.set(key, 1);
            result = 1;
        }
        return result;
    }

    @Override
    public Long getSN(String method) {
        String key = VideoManagerConstants.SIP_SN_PREFIX  + userSetting.getServerId() + "_" +  method;

        long result =  RedisUtil.incr(key, 1L);
        if (result > Integer.MAX_VALUE) {
            RedisUtil.set(key, 1);
            result = 1;
        }
        return result;
    }

    @Override
    public void resetAllCSEQ() {
        String scanKey = VideoManagerConstants.SIP_CSEQ_PREFIX  + userSetting.getServerId() + "_*";
        List<Object> keys = RedisUtil.scan(scanKey);
        for (Object o : keys) {
            String key = (String) o;
            RedisUtil.set(key, 1);
        }
    }

    @Override
    public void resetAllSN() {
        String scanKey = VideoManagerConstants.SIP_SN_PREFIX  + userSetting.getServerId() + "_*";
        List<Object> keys = RedisUtil.scan(scanKey);
        for (Object o : keys) {
            String key = (String) o;
            RedisUtil.set(key, 1);
        }
    }

    /**
     * 开始播放时将流存入redis
     *
     * @return
     */
    @Override
    public boolean startPlay(StreamInfo stream) {

        return RedisUtil.set(String.format("%S_%s_%s_%s_%s_%s", VideoManagerConstants.PLAYER_PREFIX, userSetting.getServerId(),
                        stream.getMediaServerId(), stream.getStream(), stream.getDeviceID(), stream.getChannelId()),
                stream);
    }

    /**
     * 停止播放时从redis删除
     *
     * @return
     */
    @Override
    public boolean stopPlay(StreamInfo streamInfo) {
        if (streamInfo == null) {
            return false;
        }
        return RedisUtil.del(String.format("%S_%s_%s_%s_%s_%s", VideoManagerConstants.PLAYER_PREFIX,
                userSetting.getServerId(),
                streamInfo.getMediaServerId(),
                streamInfo.getStream(),
                streamInfo.getDeviceID(),
                streamInfo.getChannelId()));
    }

    /**
     * 查询播放列表
     * @return
     */
    @Override
    public StreamInfo queryPlay(StreamInfo streamInfo) {
        return (StreamInfo)RedisUtil.get(String.format("%S_%s_%s_%s_%s_%s",
                VideoManagerConstants.PLAYER_PREFIX,
                userSetting.getServerId(),
                streamInfo.getMediaServerId(),
                streamInfo.getStream(),
                streamInfo.getDeviceID(),
                streamInfo.getChannelId()));
    }
    @Override
    public StreamInfo queryPlayByStreamId(String streamId) {
        List<Object> playLeys = RedisUtil.scan(String.format("%S_%s_*_%s_*", VideoManagerConstants.PLAYER_PREFIX, userSetting.getServerId(), streamId));
        if (playLeys == null || playLeys.size() == 0) {
            return null;
        }
        return (StreamInfo)RedisUtil.get(playLeys.get(0).toString());
    }

    @Override
    public StreamInfo queryPlayByDevice(String deviceId, String channelId) {
        List<Object> playLeys = RedisUtil.scan(String.format("%S_%s_*_*_%s_%s", VideoManagerConstants.PLAYER_PREFIX,
                userSetting.getServerId(),
                deviceId,
                channelId));
        if (playLeys == null || playLeys.size() == 0) {
            return null;
        }
        return (StreamInfo)RedisUtil.get(playLeys.get(0).toString());
    }

    @Override
    public Map<String, StreamInfo> queryPlayByDeviceId(String deviceId) {
        Map<String, StreamInfo> streamInfos = new HashMap<>();
        List<Object> players = RedisUtil.scan(String.format("%S_%s_*_*_%s_*", VideoManagerConstants.PLAYER_PREFIX, userSetting.getServerId(),deviceId));
        if (players.size() == 0) {
            return streamInfos;
        }
        for (Object player : players) {
            String key = (String) player;
            StreamInfo streamInfo = JsonUtil.redisJsonToObject(key, StreamInfo.class);
            if (Objects.isNull(streamInfo)) {
                continue;
            }
            streamInfos.put(streamInfo.getDeviceID() + "_" + streamInfo.getChannelId(), streamInfo);
        }
        return streamInfos;
    }


    @Override
    public boolean startPlayback(StreamInfo stream, String callId) {
        return RedisUtil.set(String.format("%S_%s_%s_%s_%s_%s_%s", VideoManagerConstants.PLAY_BLACK_PREFIX,
                userSetting.getServerId(), stream.getMediaServerId(), stream.getDeviceID(), stream.getChannelId(), stream.getStream(), callId), stream);
    }

    @Override
    public boolean startDownload(StreamInfo stream, String callId) {
        boolean result;
        if (stream.getProgress() == 1) {
            result = RedisUtil.set(String.format("%S_%s_%s_%s_%s_%s_%s", VideoManagerConstants.DOWNLOAD_PREFIX,
                    userSetting.getServerId(), stream.getMediaServerId(), stream.getDeviceID(), stream.getChannelId(), stream.getStream(), callId), stream);
        }else {
            result = RedisUtil.set(String.format("%S_%s_%s_%s_%s_%s_%s", VideoManagerConstants.DOWNLOAD_PREFIX,
                    userSetting.getServerId(), stream.getMediaServerId(), stream.getDeviceID(), stream.getChannelId(), stream.getStream(), callId), stream, 60*60);
        }
        return result;
    }
    @Override
    public boolean stopDownload(String deviceId, String channelId, String stream, String callId) {
        DeviceChannel deviceChannel = deviceChannelMapper.queryChannel(deviceId, channelId);
        if (deviceChannel != null) {
            deviceChannel.setStreamId(null);
            deviceChannel.setDeviceId(deviceId);
            deviceChannelMapper.update(deviceChannel);
        }
        if (deviceId == null) {
            deviceId = "*";
        }
        if (channelId == null) {
            channelId = "*";
        }
        if (stream == null) {
            stream = "*";
        }
        if (callId == null) {
            callId = "*";
        }
        String key = String.format("%S_%s_*_%s_%s_%s_%s", VideoManagerConstants.DOWNLOAD_PREFIX,
                userSetting.getServerId(),
                deviceId,
                channelId,
                stream,
                callId
        );
        List<Object> scan = RedisUtil.scan(key);
        if (scan.size() > 0) {
            for (Object keyObj : scan) {
                RedisUtil.del((String) keyObj);
            }
        }
        return true;
    }

    @Override
    public boolean stopPlayback(String deviceId, String channelId, String stream, String callId) {
        DeviceChannel deviceChannel = deviceChannelMapper.queryChannel(deviceId, channelId);
        if (deviceChannel != null) {
            deviceChannel.setStreamId(null);
            deviceChannel.setDeviceId(deviceId);
            deviceChannelMapper.update(deviceChannel);
        }
        if (deviceId == null) {
            deviceId = "*";
        }
        if (channelId == null) {
            channelId = "*";
        }
        if (stream == null) {
            stream = "*";
        }
        if (callId == null) {
            callId = "*";
        }
        String key = String.format("%S_%s_*_%s_%s_%s_%s", VideoManagerConstants.PLAY_BLACK_PREFIX,
                userSetting.getServerId(),
                deviceId,
                channelId,
                stream,
                callId
        );
        List<Object> scan = RedisUtil.scan(key);
        if (scan.size() > 0) {
            for (Object keyObj : scan) {
                RedisUtil.del((String) keyObj);
            }
        }
        return true;
    }

    @Override
    public StreamInfo queryPlayback(String deviceId, String channelId, String stream, String callId) {
        if (stream == null && callId == null) {
            return null;
        }
        if (deviceId == null) {
            deviceId = "*";
        }
        if (channelId == null) {
            channelId = "*";
        }
        if (stream == null) {
            stream = "*";
        }
        if (callId == null) {
            callId = "*";
        }
        String key = String.format("%S_%s_*_%s_%s_%s_%s", VideoManagerConstants.PLAY_BLACK_PREFIX,
                userSetting.getServerId(),
                deviceId,
                channelId,
                stream,
                callId
        );
        List<Object> streamInfoScan = RedisUtil.scan(key);
        if (streamInfoScan.size() > 0) {
            return (StreamInfo) RedisUtil.get((String) streamInfoScan.get(0));
        }else {
            return null;
        }
    }

    @Override
    public String queryPlaybackForKey(String deviceId, String channelId, String stream, String callId) {
        if (stream == null && callId == null) {
            return null;
        }
        if (deviceId == null) {
            deviceId = "*";
        }
        if (channelId == null) {
            channelId = "*";
        }
        if (stream == null) {
            stream = "*";
        }
        if (callId == null) {
            callId = "*";
        }
        String key = String.format("%S_%s_*_%s_%s_%s_%s", VideoManagerConstants.PLAY_BLACK_PREFIX,
                userSetting.getServerId(),
                deviceId,
                channelId,
                stream,
                callId
        );
        List<Object> streamInfoScan = RedisUtil.scan(key);
        return (String) streamInfoScan.get(0);
    }

    @Override
    public void updatePlatformCatchInfo(ParentPlatformCatch parentPlatformCatch) {
        String key = VideoManagerConstants.PLATFORM_CATCH_PREFIX  + userSetting.getServerId() + "_" +  parentPlatformCatch.getId();
        RedisUtil.set(key, parentPlatformCatch);
    }

    @Override
    public ParentPlatformCatch queryPlatformCatchInfo(String platformGbId) {
        return (ParentPlatformCatch)RedisUtil.get(VideoManagerConstants.PLATFORM_CATCH_PREFIX + userSetting.getServerId() + "_" + platformGbId);
    }

    @Override
    public void delPlatformCatchInfo(String platformGbId) {
        RedisUtil.del(VideoManagerConstants.PLATFORM_CATCH_PREFIX + userSetting.getServerId() + "_" + platformGbId);
    }

    @Override
    public void delPlatformKeepalive(String platformGbId) {
        RedisUtil.del(VideoManagerConstants.PLATFORM_KEEPALIVE_PREFIX + userSetting.getServerId() + "_" + platformGbId);
    }

    @Override
    public void delPlatformRegister(String platformGbId) {
        RedisUtil.del(VideoManagerConstants.PLATFORM_REGISTER_PREFIX + userSetting.getServerId() + "_" + platformGbId);
    }


    @Override
    public void updatePlatformRegisterInfo(String callId, PlatformRegisterInfo platformRegisterInfo) {
        String key = VideoManagerConstants.PLATFORM_REGISTER_INFO_PREFIX + userSetting.getServerId() + "_" + callId;
        RedisUtil.set(key, platformRegisterInfo, 30);
    }


    @Override
    public PlatformRegisterInfo queryPlatformRegisterInfo(String callId) {
        return (PlatformRegisterInfo)RedisUtil.get(VideoManagerConstants.PLATFORM_REGISTER_INFO_PREFIX + userSetting.getServerId() + "_" + callId);
    }

    @Override
    public void delPlatformRegisterInfo(String callId) {
        RedisUtil.del(VideoManagerConstants.PLATFORM_REGISTER_INFO_PREFIX + userSetting.getServerId() + "_" + callId);
    }

    @Override
    public void cleanPlatformRegisterInfos() {
        List regInfos = RedisUtil.scan(VideoManagerConstants.PLATFORM_REGISTER_INFO_PREFIX + userSetting.getServerId() + "_" + "*");
        for (Object key : regInfos) {
            RedisUtil.del(key.toString());
        }
    }

    @Override
    public void updateSendRTPSever(SendRtpItem sendRtpItem) {

        String key = VideoManagerConstants.PLATFORM_SEND_RTP_INFO_PREFIX +
                userSetting.getServerId() + "_"
                + sendRtpItem.getMediaServerId() + "_"
                + sendRtpItem.getPlatformId() + "_"
                + sendRtpItem.getChannelId() + "_"
                + sendRtpItem.getStreamId() + "_"
                + sendRtpItem.getCallId();
        RedisUtil.set(key, sendRtpItem);
    }

    @Override
    public SendRtpItem querySendRTPServer(String platformGbId, String channelId, String streamId, String callId) {
        if (platformGbId == null) {
            platformGbId = "*";
        }
        if (channelId == null) {
            channelId = "*";
        }
        if (streamId == null) {
            streamId = "*";
        }
        if (callId == null) {
            callId = "*";
        }
        String key = VideoManagerConstants.PLATFORM_SEND_RTP_INFO_PREFIX
                + userSetting.getServerId() + "_*_"
                + platformGbId + "_"
                + channelId + "_"
                + streamId + "_"
                + callId;
        List<Object> scan = RedisUtil.scan(key);
        if (scan.size() > 0) {
            return (SendRtpItem)RedisUtil.get((String)scan.get(0));
        }else {
            return null;
        }
    }

    @Override
    public List<SendRtpItem> querySendRTPServerByChnnelId(String channelId) {
        if (channelId == null) {
            return null;
        }
        String platformGbId = "*";
        String callId = "*";
        String streamId = "*";
        String key = VideoManagerConstants.PLATFORM_SEND_RTP_INFO_PREFIX
                + userSetting.getServerId() + "_*_"
                + platformGbId + "_"
                + channelId + "_"
                + streamId + "_"
                + callId;
        List<Object> scan = RedisUtil.scan(key);
        List<SendRtpItem> result = new ArrayList<>();
        for (Object o : scan) {
            result.add((SendRtpItem) RedisUtil.get((String) o));
        }
        return result;
    }

    @Override
    public List<SendRtpItem> querySendRTPServerByStream(String stream) {
        if (stream == null) {
            return null;
        }
        String platformGbId = "*";
        String callId = "*";
        String channelId = "*";
        String key = VideoManagerConstants.PLATFORM_SEND_RTP_INFO_PREFIX
                + userSetting.getServerId() + "_*_"
                + platformGbId + "_"
                + channelId + "_"
                + stream + "_"
                + callId;
        List<Object> scan = RedisUtil.scan(key);
        List<SendRtpItem> result = new ArrayList<>();
        for (Object o : scan) {
            result.add((SendRtpItem) RedisUtil.get((String) o));
        }
        return result;
    }

    @Override
    public List<SendRtpItem> querySendRTPServer(String platformGbId) {
        if (platformGbId == null) {
            platformGbId = "*";
        }
        String key = VideoManagerConstants.PLATFORM_SEND_RTP_INFO_PREFIX
                + userSetting.getServerId() + "_*_"
                + platformGbId + "_*" + "_*" + "_*";
        List<Object> queryResult = RedisUtil.scan(key);
        List<SendRtpItem> result= new ArrayList<>();

        for (Object o : queryResult) {
            String keyItem = (String) o;
            result.add((SendRtpItem) RedisUtil.get(keyItem));
        }

        return result;
    }

    /**
     * 删除RTP推送信息缓存
     * @param platformGbId
     * @param channelId
     */
    @Override
    public void deleteSendRTPServer(String platformGbId, String channelId, String callId, String streamId) {
        if (streamId == null) {
            streamId = "*";
        }
        if (callId == null) {
            callId = "*";
        }
        String key = VideoManagerConstants.PLATFORM_SEND_RTP_INFO_PREFIX
                + userSetting.getServerId() + "_*_"
                + platformGbId + "_"
                + channelId + "_"
                + streamId + "_"
                + callId;
        List<Object> scan = RedisUtil.scan(key);
        if (scan.size() > 0) {
            for (Object keyStr : scan) {
                RedisUtil.del((String)keyStr);
            }
        }
    }

    @Override
    public List<SendRtpItem> queryAllSendRTPServer() {
        String key = VideoManagerConstants.PLATFORM_SEND_RTP_INFO_PREFIX
                + userSetting.getServerId() + "_*";
        List<Object> queryResult = RedisUtil.scan(key);
        List<SendRtpItem> result= new ArrayList<>();

        for (Object o : queryResult) {
            String keyItem = (String) o;
            result.add((SendRtpItem) RedisUtil.get(keyItem));
        }

        return result;
    }

    /**
     * 查询某个通道是否存在上级点播（RTP推送）
     * @param channelId
     */
    @Override
    public boolean isChannelSendingRTP(String channelId) {
        String key = VideoManagerConstants.PLATFORM_SEND_RTP_INFO_PREFIX
                + userSetting.getServerId() + "_*_*_"
                + channelId + "*_" + "*_";
        List<Object> RtpStreams = RedisUtil.scan(key);
        if (RtpStreams.size() > 0) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void clearCatchByDeviceId(String deviceId) {
        List<Object> playLeys = RedisUtil.scan(String.format("%S_%s_*_%s_*", VideoManagerConstants.PLAYER_PREFIX,
                userSetting.getServerId(),
                deviceId));
        if (playLeys.size() > 0) {
            for (Object key : playLeys) {
                RedisUtil.del(key.toString());
            }
        }

        List<Object> playBackers = RedisUtil.scan(String.format("%S_%s_*_%s_*_*_*", VideoManagerConstants.PLAY_BLACK_PREFIX,
                userSetting.getServerId(),
                deviceId));
        if (playBackers.size() > 0) {
            for (Object key : playBackers) {
                RedisUtil.del(key.toString());
            }
        }

        List<Object> deviceCache = RedisUtil.scan(String.format("%S%s_%s", VideoManagerConstants.DEVICE_PREFIX,
                userSetting.getServerId(),
                deviceId));
        if (deviceCache.size() > 0) {
            for (Object key : deviceCache) {
                RedisUtil.del(key.toString());
            }
        }
    }

    @Override
    public void updateWVPInfo(JSONObject jsonObject, int time) {
        String key = VideoManagerConstants.WVP_SERVER_PREFIX + userSetting.getServerId();
        RedisUtil.set(key, jsonObject, time);
    }

    @Override
    public void sendStreamChangeMsg(String type, JSONObject jsonObject) {
        String key = VideoManagerConstants.WVP_MSG_STREAM_CHANGE_PREFIX + type;
        logger.info("[redis 流变化事件] {}: {}", key, jsonObject.toString());
        RedisUtil.convertAndSend(key, jsonObject);
    }

    @Override
    public void addStream(MediaServerItem mediaServerItem, String type, String app, String streamId, OnStreamChangedHookParam onStreamChangedHookParam) {
        // 查找是否使用了callID
        StreamAuthorityInfo streamAuthorityInfo = getStreamAuthorityInfo(app, streamId);
        String key = VideoManagerConstants.WVP_SERVER_STREAM_PREFIX  + userSetting.getServerId() + "_" + type + "_" + app + "_" + streamId + "_" + mediaServerItem.getId();
        if (streamAuthorityInfo != null) {
            onStreamChangedHookParam.setCallId(streamAuthorityInfo.getCallId());
        }
        RedisUtil.set(key, onStreamChangedHookParam);
    }

    @Override
    public void removeStream(String mediaServerId, String type, String app, String streamId) {
        String key = VideoManagerConstants.WVP_SERVER_STREAM_PREFIX + userSetting.getServerId() + "_" + type + "_"  + app + "_" + streamId + "_" + mediaServerId;
        RedisUtil.del(key);
    }

    @Override
    public StreamInfo queryDownload(String deviceId, String channelId, String stream, String callId) {
        if (stream == null && callId == null) {
            return null;
        }
        if (deviceId == null) {
            deviceId = "*";
        }
        if (channelId == null) {
            channelId = "*";
        }
        if (stream == null) {
            stream = "*";
        }
        if (callId == null) {
            callId = "*";
        }
        String key = String.format("%S_%s_*_%s_%s_%s_%s", VideoManagerConstants.DOWNLOAD_PREFIX,
                userSetting.getServerId(),
                deviceId,
                channelId,
                stream,
                callId
        );
        List<Object> streamInfoScan = RedisUtil.scan(key);
        if (streamInfoScan.size() > 0) {
            return (StreamInfo) RedisUtil.get((String) streamInfoScan.get(0));
        }else {
            return null;
        }
    }

    @Override
    public ThirdPartyGB queryMemberNoGBId(String queryKey) {
        String key = VideoManagerConstants.WVP_STREAM_GB_ID_PREFIX + queryKey;
        return JsonUtil.redisJsonToObject(key, ThirdPartyGB.class);
    }

    @Override
    public void removeStream(String mediaServerId, String type) {
        String key = VideoManagerConstants.WVP_SERVER_STREAM_PREFIX + userSetting.getServerId() + "_" + type + "_*_*_" + mediaServerId;
        List<Object> streams = RedisUtil.scan(key);
        for (Object stream : streams) {
            RedisUtil.del((String) stream);
        }
    }

    @Override
    public List<OnStreamChangedHookParam> getStreams(String mediaServerId, String type) {
        List<OnStreamChangedHookParam> result = new ArrayList<>();
        String key = VideoManagerConstants.WVP_SERVER_STREAM_PREFIX + userSetting.getServerId() + "_" + type + "_*_*_" + mediaServerId;
        List<Object> streams = RedisUtil.scan(key);
        for (Object stream : streams) {
            OnStreamChangedHookParam onStreamChangedHookParam = (OnStreamChangedHookParam)RedisUtil.get((String) stream);
            result.add(onStreamChangedHookParam);
        }
        return result;
    }

    @Override
    public void updateDevice(Device device) {
        String key = VideoManagerConstants.DEVICE_PREFIX + userSetting.getServerId() + "_" + device.getDeviceId();
        RedisUtil.set(key, device);
    }

    @Override
    public void removeDevice(String deviceId) {
        String key = VideoManagerConstants.DEVICE_PREFIX + userSetting.getServerId() + "_" + deviceId;
        RedisUtil.del(key);
    }

    @Override
    public void removeAllDevice() {
        String scanKey = VideoManagerConstants.DEVICE_PREFIX + userSetting.getServerId() + "_*";
        List<Object> keys = RedisUtil.scan(scanKey);
        for (Object key : keys) {
            RedisUtil.del((String) key);
        }
    }

    @Override
    public List<Device> getAllDevices() {
        String scanKey = VideoManagerConstants.DEVICE_PREFIX + userSetting.getServerId() + "_*";
        List<Device> result = new ArrayList<>();
        List<Object> keys = RedisUtil.scan(scanKey);
        for (Object o : keys) {
            String key = (String) o;
            Device device = JsonUtil.redisJsonToObject(key, Device.class);
            if (Objects.nonNull(device)) { // 只取没有存过得
                result.add(JsonUtil.redisJsonToObject(key, Device.class));
            }
        }

        return result;
    }

    @Override
    public Device getDevice(String deviceId) {
        String key = VideoManagerConstants.DEVICE_PREFIX + userSetting.getServerId() + "_" + deviceId;
        return JsonUtil.redisJsonToObject(key, Device.class);
    }

    @Override
    public void updateGpsMsgInfo(GPSMsgInfo gpsMsgInfo) {
        String key = VideoManagerConstants.WVP_STREAM_GPS_MSG_PREFIX + userSetting.getServerId() + "_" + gpsMsgInfo.getId();
        RedisUtil.set(key, gpsMsgInfo, 60); // 默认GPS消息保存1分钟
    }

    @Override
    public GPSMsgInfo getGpsMsgInfo(String gbId) {
        String key = VideoManagerConstants.WVP_STREAM_GPS_MSG_PREFIX + userSetting.getServerId() + "_" + gbId;
        return JsonUtil.redisJsonToObject(key, GPSMsgInfo.class);
    }

    @Override
    public List<GPSMsgInfo> getAllGpsMsgInfo() {
        String scanKey = VideoManagerConstants.WVP_STREAM_GPS_MSG_PREFIX + userSetting.getServerId() + "_*";
        List<GPSMsgInfo> result = new ArrayList<>();
        List<Object> keys = RedisUtil.scan(scanKey);
        for (Object o : keys) {
            String key = (String) o;
            GPSMsgInfo gpsMsgInfo = JsonUtil.redisJsonToObject(key, GPSMsgInfo.class);
            if (Objects.nonNull(gpsMsgInfo) && !gpsMsgInfo.isStored()) { // 只取没有存过得
                result.add(JsonUtil.redisJsonToObject(key, GPSMsgInfo.class));
            }
        }

        return result;
    }

    @Override
    public void updateStreamAuthorityInfo(String app, String stream, StreamAuthorityInfo streamAuthorityInfo) {
        String key = VideoManagerConstants.MEDIA_STREAM_AUTHORITY + userSetting.getServerId() + "_" + app+ "_" + stream;
        RedisUtil.set(key, streamAuthorityInfo);
    }

    @Override
    public void removeStreamAuthorityInfo(String app, String stream) {
        String key = VideoManagerConstants.MEDIA_STREAM_AUTHORITY + userSetting.getServerId() + "_" + app+ "_" + stream ;
        RedisUtil.del(key);
    }

    @Override
    public StreamAuthorityInfo getStreamAuthorityInfo(String app, String stream) {
        String key = VideoManagerConstants.MEDIA_STREAM_AUTHORITY + userSetting.getServerId() + "_" + app+ "_" + stream ;
        return JsonUtil.redisJsonToObject(key, StreamAuthorityInfo.class);

    }

    @Override
    public List<StreamAuthorityInfo> getAllStreamAuthorityInfo() {
        String scanKey = VideoManagerConstants.MEDIA_STREAM_AUTHORITY + userSetting.getServerId() + "_*_*" ;
        List<StreamAuthorityInfo> result = new ArrayList<>();
        List<Object> keys = RedisUtil.scan(scanKey);
        for (Object o : keys) {
            String key = (String) o;
            result.add(JsonUtil.redisJsonToObject(key, StreamAuthorityInfo.class));
        }
        return result;
    }


    @Override
    public OnStreamChangedHookParam getStreamInfo(String app, String streamId, String mediaServerId) {
        String scanKey = VideoManagerConstants.WVP_SERVER_STREAM_PREFIX  + userSetting.getServerId() + "_*_" + app + "_" + streamId + "_" + mediaServerId;

        OnStreamChangedHookParam result = null;
        List<Object> keys = RedisUtil.scan(scanKey);
        if (keys.size() > 0) {
            String key = (String) keys.get(0);
            result = JsonUtil.redisJsonToObject(key, OnStreamChangedHookParam.class);
        }

        return result;
    }

    @Override
    public void addCpuInfo(double cpuInfo) {
        String key = VideoManagerConstants.SYSTEM_INFO_CPU_PREFIX + userSetting.getServerId();
        Map<String, String> infoMap = new HashMap<>();
        infoMap.put("time", DateUtil.getNow());
        infoMap.put("data", cpuInfo + "");
        RedisUtil.lSet(key, infoMap);
        // 每秒一个，最多只存30个
        if (RedisUtil.lGetListSize(key) >= 30) {
            for (int i = 0; i < RedisUtil.lGetListSize(key) - 30; i++) {
                RedisUtil.lLeftPop(key);
            }
        }
    }

    @Override
    public void addMemInfo(double memInfo) {
        String key = VideoManagerConstants.SYSTEM_INFO_MEM_PREFIX + userSetting.getServerId();
        Map<String, String> infoMap = new HashMap<>();
        infoMap.put("time", DateUtil.getNow());
        infoMap.put("data", memInfo + "");
        RedisUtil.lSet(key, infoMap);
        // 每秒一个，最多只存30个
        if (RedisUtil.lGetListSize(key) >= 30) {
            for (int i = 0; i < RedisUtil.lGetListSize(key) - 30; i++) {
                RedisUtil.lLeftPop(key);
            }
        }
    }

    @Override
    public void addNetInfo(Map<String, Double> networkInterfaces) {
        String key = VideoManagerConstants.SYSTEM_INFO_NET_PREFIX + userSetting.getServerId();
        Map<String, Object> infoMap = new HashMap<>();
        infoMap.put("time", DateUtil.getNow());
        for (String netKey : networkInterfaces.keySet()) {
            infoMap.put(netKey, networkInterfaces.get(netKey));
        }
        RedisUtil.lSet(key, infoMap);
        // 每秒一个，最多只存30个
        if (RedisUtil.lGetListSize(key) >= 30) {
            for (int i = 0; i < RedisUtil.lGetListSize(key) - 30; i++) {
                RedisUtil.lLeftPop(key);
            }
        }
    }

    @Override
    public void addDiskInfo(List<Map<String, Object>> diskInfo) {

        String key = VideoManagerConstants.SYSTEM_INFO_DISK_PREFIX + userSetting.getServerId();
        RedisUtil.set(key, diskInfo);
    }

    @Override
    public SystemAllInfo getSystemInfo() {
        String cpuKey = VideoManagerConstants.SYSTEM_INFO_CPU_PREFIX + userSetting.getServerId();
        String memKey = VideoManagerConstants.SYSTEM_INFO_MEM_PREFIX + userSetting.getServerId();
        String netKey = VideoManagerConstants.SYSTEM_INFO_NET_PREFIX + userSetting.getServerId();
        String diskKey = VideoManagerConstants.SYSTEM_INFO_DISK_PREFIX + userSetting.getServerId();
        SystemAllInfo systemAllInfo = new SystemAllInfo();
        systemAllInfo.setCpu(RedisUtil.lGet(cpuKey, 0, -1));
        systemAllInfo.setMem(RedisUtil.lGet(memKey, 0, -1));
        systemAllInfo.setNet(RedisUtil.lGet(netKey, 0, -1));

        systemAllInfo.setDisk(RedisUtil.get(diskKey));
        systemAllInfo.setNetTotal(SystemInfoUtils.getNetworkTotal());
        return systemAllInfo;
    }

    @Override
    public void sendMobilePositionMsg(JSONObject jsonObject) {
        String key = VideoManagerConstants.VM_MSG_SUBSCRIBE_MOBILE_POSITION;
        logger.info("[redis发送通知] 移动位置 {}: {}", key, jsonObject.toString());
        RedisUtil.convertAndSend(key, jsonObject);
    }

    @Override
    public void sendStreamPushRequestedMsg(MessageForPushChannel msg) {
        String key = VideoManagerConstants.VM_MSG_STREAM_PUSH_REQUESTED;
        logger.info("[redis发送通知] 推流被请求 {}: {}/{}", key, msg.getApp(), msg.getStream());
        RedisUtil.convertAndSend(key, (JSONObject)JSON.toJSON(msg));
    }

    @Override
    public void sendAlarmMsg(AlarmChannelMessage msg) {
        String key = VideoManagerConstants.VM_MSG_SUBSCRIBE_ALARM_RECEIVE;
        logger.info("[redis发送通知] 报警{}: {}", key, JSON.toJSON(msg));
        RedisUtil.convertAndSend(key, (JSONObject)JSON.toJSON(msg));
    }

    @Override
    public boolean deviceIsOnline(String deviceId) {
        return getDevice(deviceId).getOnline() == 1;
    }


    @Override
    public void sendStreamPushRequestedMsgForStatus() {
        String key = VideoManagerConstants.VM_MSG_GET_ALL_ONLINE_REQUESTED;
        logger.info("[redis通知]获取所有推流设备的状态");
        JSONObject jsonObject = new JSONObject();
        jsonObject.put(key, key);
        RedisUtil.convertAndSend(key, jsonObject);
    }

    @Override
    public int getPushStreamCount(String id) {
        String key = VideoManagerConstants.WVP_SERVER_STREAM_PREFIX + userSetting.getServerId() + "_PUSH_*_*_" + id;
        return RedisUtil.scan(key).size();
    }

    @Override
    public int getProxyStreamCount(String id) {
        String key = VideoManagerConstants.WVP_SERVER_STREAM_PREFIX + userSetting.getServerId() + "_PULL_*_*_" + id;
        return RedisUtil.scan(key).size();
    }

    @Override
    public int getGbReceiveCount(String id) {
        String playKey = VideoManagerConstants.PLAYER_PREFIX + "_" + userSetting.getServerId() + "_" + id + "_*";
        String playBackKey = VideoManagerConstants.PLAY_BLACK_PREFIX + "_" + userSetting.getServerId() + "_" + id + "_*";
        String downloadKey = VideoManagerConstants.DOWNLOAD_PREFIX + "_" + userSetting.getServerId() + "_" + id + "_*";

        return RedisUtil.scan(playKey).size() + RedisUtil.scan(playBackKey).size() + RedisUtil.scan(downloadKey).size();
    }

    @Override
    public int getGbSendCount(String id) {
        String key = VideoManagerConstants.PLATFORM_SEND_RTP_INFO_PREFIX
                + userSetting.getServerId() + "_*_" + id + "_*";
        return RedisUtil.scan(key).size();
    }
}
