/*
 * Copyright (C) 2014
 * University of Massachusetts
 * All Rights Reserved 
 *
 * Initial developer(s): Westy.
 */
package edu.umass.cs.gns.commands.account;

import edu.umass.cs.gns.clientsupport.AccountAccess;
import edu.umass.cs.gns.clientsupport.CommandRequestHandler;
import static edu.umass.cs.gns.clientsupport.Defs.*;
import edu.umass.cs.gns.commands.CommandDefs;
import edu.umass.cs.gns.commands.CommandModule;
import edu.umass.cs.gns.commands.GnsCommand;
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
public class VerifyAccount extends GnsCommand {

  public VerifyAccount(CommandModule module) {
    super(module);
  }

  @Override
  public String[] getCommandParameters() {
    return new String[]{GUID, CODE};
  }

  @Override
  public String getCommandName() {
    return VERIFYACCOUNT;
  }

  @Override
  public String execute(JSONObject json) throws InvalidKeyException, InvalidKeySpecException,
          JSONException, NoSuchAlgorithmException, SignatureException {
    if (CommandDefs.handleAcccountCommandsAtNameServer) {
      return CommandRequestHandler.sendCommandRequest(json);
    } else {
      String guid = json.getString(GUID);
      String code = json.getString(CODE);
      return AccountAccess.verifyAccount(guid, code);
    }
  }

  @Override
  public String getCommandDescription() {
    return "Handles the completion of the verification process for a guid by supplying the correct code.";
  }
}
