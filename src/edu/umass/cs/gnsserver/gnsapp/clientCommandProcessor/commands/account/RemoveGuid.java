/*
 *
 *  Copyright (c) 2015 University of Massachusetts
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you
 *  may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 *
 *  Initial developer(s): Westy
 *
 */
package edu.umass.cs.gnsserver.gnsapp.clientCommandProcessor.commands.account;

import static edu.umass.cs.gnscommon.GnsProtocol.*;
import edu.umass.cs.gnscommon.exceptions.client.GnsClientException;
import edu.umass.cs.gnsserver.gnsapp.clientCommandProcessor.ClientRequestHandlerInterface;
import edu.umass.cs.gnsserver.gnsapp.clientCommandProcessor.commandSupport.AccountAccess;
import edu.umass.cs.gnsserver.gnsapp.clientCommandProcessor.commandSupport.AccountInfo;
import edu.umass.cs.gnsserver.gnsapp.clientCommandProcessor.commandSupport.CommandResponse;
import edu.umass.cs.gnsserver.gnsapp.clientCommandProcessor.commandSupport.GuidInfo;
import edu.umass.cs.gnsserver.gnsapp.clientCommandProcessor.commands.CommandModule;
import edu.umass.cs.gnsserver.gnsapp.clientCommandProcessor.commands.CommandType;
import edu.umass.cs.gnsserver.gnsapp.clientCommandProcessor.commands.GnsCommand;

import edu.umass.cs.gnsserver.gnsapp.clientSupport.NSAccessSupport;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;

import org.json.JSONException;
import org.json.JSONObject;

/**
 *
 * @author westy
 */
public class RemoveGuid extends GnsCommand {

  /**
   * Creates a RemoveGuid instance.
   *
   * @param module
   */
  public RemoveGuid(CommandModule module) {
    super(module);
  }

  @Override
  public CommandType getCommandType() {
    return CommandType.RemoveGuid;
  }

  @Override
  public String[] getCommandParameters() {
    return new String[]{ACCOUNT_GUID, GUID, SIGNATURE, SIGNATUREFULLMESSAGE};
  }

  @Override
  public String getCommandName() {
    return REMOVE_GUID;
  }

  @Override
  public CommandResponse<String> execute(JSONObject json, ClientRequestHandlerInterface handler) throws InvalidKeyException, InvalidKeySpecException,
          JSONException, NoSuchAlgorithmException, SignatureException, UnsupportedEncodingException {
    String guidToRemove = json.getString(GUID);
    String accountGuid = json.optString(ACCOUNT_GUID, null);
    String signature = json.getString(SIGNATURE);
    String message = json.getString(SIGNATUREFULLMESSAGE);
    GuidInfo accountGuidInfo = null;
    GuidInfo guidInfoToRemove;
    if ((guidInfoToRemove = AccountAccess.lookupGuidInfo(guidToRemove, handler, true)) == null) {
      return new CommandResponse<>(BAD_RESPONSE + " " + BAD_GUID + " " + guidToRemove);
    }
    if (accountGuid != null) {
      if ((accountGuidInfo = AccountAccess.lookupGuidInfo(accountGuid, handler, true)) == null) {
        return new CommandResponse<>(BAD_RESPONSE + " " + BAD_GUID + " " + accountGuid);
      }
    }
    try {
      if (NSAccessSupport.verifySignature(accountGuidInfo != null ? accountGuidInfo.getPublicKey()
              : guidInfoToRemove.getPublicKey(), signature, message)) {
        AccountInfo accountInfo = null;
        if (accountGuid != null) {
          accountInfo = AccountAccess.lookupAccountInfoFromGuid(accountGuid, handler, true);
          if (accountInfo == null) {
            return new CommandResponse<>(BAD_RESPONSE + " " + BAD_ACCOUNT + " " + accountGuid);
          }
        }
        return AccountAccess.removeGuid(guidInfoToRemove, accountInfo, handler);
      } else {
        return new CommandResponse<>(BAD_RESPONSE + " " + BAD_SIGNATURE);
      }
    } catch (GnsClientException | IOException e) {
      return new CommandResponse<>(BAD_RESPONSE + " " + GENERIC_ERROR + " " + e.getMessage());
    }
  }

  @Override
  public String getCommandDescription() {
    return "Removes the GUID from the account associated with the ACCOUNT_GUID. "
            + "Must be signed by the account guid or the guid if account guid is not provided. "
            + "Returns " + BAD_GUID + " if the GUID has not been registered.";

  }
}