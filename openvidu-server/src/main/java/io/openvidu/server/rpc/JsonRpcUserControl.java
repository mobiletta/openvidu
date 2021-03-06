/*
 * (C) Copyright 2015 Kurento (http://kurento.org/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.openvidu.server.rpc;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import org.kurento.jsonrpc.Session;
import org.kurento.jsonrpc.Transaction;
import org.kurento.jsonrpc.message.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.google.gson.JsonObject;

import io.openvidu.client.OpenViduException;
import io.openvidu.client.OpenViduException.Code;
import io.openvidu.client.internal.ProtocolElements;
import io.openvidu.server.core.NotificationRoomManager;
import io.openvidu.server.core.api.pojo.ParticipantRequest;
import io.openvidu.server.core.api.pojo.UserParticipant;
import io.openvidu.server.security.OpenviduConfiguration;

/**
 * Controls the user interactions by delegating her JSON-RPC requests to the room API.
 *
 * @author Radu Tom Vlad (rvlad@naevatec.com)
 */
public class JsonRpcUserControl {

  private static final Logger log = LoggerFactory.getLogger(JsonRpcUserControl.class);
  
  @Autowired
  protected NotificationRoomManager roomManager;
  
  @Autowired
  OpenviduConfiguration openviduConf;

  public JsonRpcUserControl() {}

  public void joinRoom(Transaction transaction, Request<JsonObject> request,
      ParticipantRequest participantRequest) throws IOException, InterruptedException,
      ExecutionException, OpenViduException {
	  
    String roomId = getStringParam(request, ProtocolElements.JOINROOM_ROOM_PARAM);
    String token = getStringParam(request, ProtocolElements.JOINROOM_TOKEN_PARAM);
    String pid = participantRequest.getParticipantId();
    
    if (openviduConf.isOpenViduSecret(getStringParam(request, ProtocolElements.JOINROOM_SECRET_PARAM))) {
    	roomManager.newInsecureUser(pid);
    }
    
    if(roomManager.getRoomManager().isParticipantInRoom(token, roomId, pid)){
    	
	    String clientMetadata = getStringParam(request, ProtocolElements.JOINROOM_METADATA_PARAM);
    	
    	if(roomManager.getRoomManager().metadataFormatCorrect(clientMetadata)){
    		
    		String userName = roomManager.newRandomUserName(token, roomId);
    		
    		roomManager.getRoomManager().setTokenClientMetadata(userName, roomId, clientMetadata);
    		
    		boolean dataChannels = false;
    	    if (request.getParams().has(ProtocolElements.JOINROOM_DATACHANNELS_PARAM)) {
    	      dataChannels = request.getParams().get(ProtocolElements.JOINROOM_DATACHANNELS_PARAM)
    	          .getAsBoolean();
    	    }
    	
    	    ParticipantSession participantSession = getParticipantSession(transaction);
    	    participantSession.setParticipantName(userName);
    	    participantSession.setRoomName(roomId);
    	    participantSession.setDataChannels(dataChannels);
    	
    	    roomManager.joinRoom(userName, roomId, dataChannels, true, participantRequest);
    	} else {
    		System.out.println("Error: metadata format is incorrect");
        	throw new OpenViduException(Code.USER_METADATA_FORMAT_INVALID_ERROR_CODE,
    				  "Unable to join room. The metadata received has an invalid format");
    	}	    
    } else {
    	System.out.println("Error: sessionId or token not valid");
    	throw new OpenViduException(Code.USER_UNAUTHORIZED_ERROR_CODE,
				  "Unable to join room. The user is not authorized");
    }
  }

  public void publishVideo(Transaction transaction, Request<JsonObject> request,
      ParticipantRequest participantRequest) {
	  
	  String pid = participantRequest.getParticipantId();
	  String participantName = roomManager.getRoomManager().getParticipantName(pid);
	  String roomName = roomManager.getRoomManager().getRoomNameFromParticipantId(pid);
	  
	  if (roomManager.getRoomManager().isPublisherInRoom(participantName, roomName, pid)) {
	  
	    String sdpOffer = getStringParam(request, ProtocolElements.PUBLISHVIDEO_SDPOFFER_PARAM);
	    boolean audioOnly = getBooleanParam(request, ProtocolElements.PUBLISHVIDEO_AUDIOONLY_PARAM);
	    boolean doLoopback = getBooleanParam(request, ProtocolElements.PUBLISHVIDEO_DOLOOPBACK_PARAM);
	
	    roomManager.publishMedia(participantRequest, sdpOffer, audioOnly, doLoopback);
	  }
	  else {
		  System.out.println("Error: user is not a publisher");
		  throw new OpenViduException(Code.USER_UNAUTHORIZED_ERROR_CODE,
				  "Unable to publish video. The user does not have a valid token");
	  }
  }

  public void unpublishVideo(Transaction transaction, Request<JsonObject> request,
      ParticipantRequest participantRequest) {
    roomManager.unpublishMedia(participantRequest);
  }

  public void receiveVideoFrom(final Transaction transaction, final Request<JsonObject> request,
      ParticipantRequest participantRequest) {

    String senderName = getStringParam(request, ProtocolElements.RECEIVEVIDEO_SENDER_PARAM);
    senderName = senderName.substring(0, senderName.indexOf("_"));

    String sdpOffer = getStringParam(request, ProtocolElements.RECEIVEVIDEO_SDPOFFER_PARAM);

    roomManager.subscribe(senderName, sdpOffer, participantRequest);
  }

  public void unsubscribeFromVideo(Transaction transaction, Request<JsonObject> request,
      ParticipantRequest participantRequest) {

    String senderName = getStringParam(request, ProtocolElements.UNSUBSCRIBEFROMVIDEO_SENDER_PARAM);
    senderName = senderName.substring(0, senderName.indexOf("_"));

    roomManager.unsubscribe(senderName, participantRequest);
  }

  public void leaveRoomAfterConnClosed(String sessionId) {
    try {
      roomManager.evictParticipant(sessionId);
      log.info("Evicted participant with sessionId {}", sessionId);
    } catch (OpenViduException e) {
      log.warn("Unable to evict: {}", e.getMessage());
      log.trace("Unable to evict user", e);
    }
  }

  public void leaveRoom(Transaction transaction, Request<JsonObject> request,
      ParticipantRequest participantRequest) {
    boolean exists = false;
    String pid = participantRequest.getParticipantId();
    // trying with room info from session
    String roomName = null;
    if (transaction != null) {
      roomName = getParticipantSession(transaction).getRoomName();
    }
    if (roomName == null) { // null when afterConnectionClosed
      log.warn("No room information found for participant with session Id {}. "
          + "Using the admin method to evict the user.", pid);
      leaveRoomAfterConnClosed(pid);
    } else {
      // sanity check, don't call leaveRoom unless the id checks out
      for (UserParticipant part : roomManager.getParticipants(roomName)) {
        if (part.getParticipantId().equals(participantRequest.getParticipantId())) {
          exists = true;
          break;
        }
      }
      if (exists) {
        log.debug("Participant with sessionId {} is leaving room {}", pid, roomName);
        roomManager.leaveRoom(participantRequest);
        log.info("Participant with sessionId {} has left room {}", pid, roomName);
      } else {
        log.warn("Participant with session Id {} not found in room {}. "
            + "Using the admin method to evict the user.", pid, roomName);
        leaveRoomAfterConnClosed(pid);
      }
    }
  }

  public void onIceCandidate(Transaction transaction, Request<JsonObject> request,
      ParticipantRequest participantRequest) {
    String endpointName = getStringParam(request, ProtocolElements.ONICECANDIDATE_EPNAME_PARAM);
    String candidate = getStringParam(request, ProtocolElements.ONICECANDIDATE_CANDIDATE_PARAM);
    String sdpMid = getStringParam(request, ProtocolElements.ONICECANDIDATE_SDPMIDPARAM);
    int sdpMLineIndex = getIntParam(request, ProtocolElements.ONICECANDIDATE_SDPMLINEINDEX_PARAM);

    roomManager.onIceCandidate(endpointName, candidate, sdpMLineIndex, sdpMid, participantRequest);
  }

  public void sendMessage(Transaction transaction, Request<JsonObject> request,
      ParticipantRequest participantRequest) {
    String userName = getStringParam(request, ProtocolElements.SENDMESSAGE_USER_PARAM);
    String roomName = getStringParam(request, ProtocolElements.SENDMESSAGE_ROOM_PARAM);
    String message = getStringParam(request, ProtocolElements.SENDMESSAGE_MESSAGE_PARAM);

    log.debug("Message from {} in room {}: '{}'", userName, roomName, message);

    roomManager.sendMessage(message, userName, roomName, participantRequest);
  }

  public void customRequest(Transaction transaction, Request<JsonObject> request,
      ParticipantRequest participantRequest) {
    throw new RuntimeException("Unsupported method");
  }

  public ParticipantSession getParticipantSession(Transaction transaction) {
    Session session = transaction.getSession();
    ParticipantSession participantSession = (ParticipantSession) session.getAttributes().get(
        ParticipantSession.SESSION_KEY);
    if (participantSession == null) {
      participantSession = new ParticipantSession();
      session.getAttributes().put(ParticipantSession.SESSION_KEY, participantSession);
    }
    return participantSession;
  }

  public static String getStringParam(Request<JsonObject> request, String key) {
    if (request.getParams() == null || request.getParams().get(key) == null) {
      throw new RuntimeException("Request element '" + key + "' is missing");
    }
    System.out.println(request.getParams().get(key));
    return request.getParams().get(key).getAsString();
  }

  public static int getIntParam(Request<JsonObject> request, String key) {
    if (request.getParams() == null || request.getParams().get(key) == null) {
      throw new RuntimeException("Request element '" + key + "' is missing");
    }
    return request.getParams().get(key).getAsInt();
  }

  public static boolean getBooleanParam(Request<JsonObject> request, String key) {
    if (request.getParams() == null || request.getParams().get(key) == null) {
      throw new RuntimeException("Request element '" + key + "' is missing");
    }
    return request.getParams().get(key).getAsBoolean();
  }
}
