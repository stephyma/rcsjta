/*
 * Copyright 2013, France Telecom
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 *    http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gsma.joyn.messaging;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import java.lang.String;

/**
 * Interface IChatEventListener
 * <p>
 * Generated from AIDL
 */
public interface IChatEventListener extends IInterface {

  public abstract static class Stub extends Binder implements IChatEventListener {

    public Stub(){
      super();
    }

    public IBinder asBinder(){
      return (IBinder) null;
    }

    public static IChatEventListener asInterface(IBinder binder){
      return (IChatEventListener) null;
    }

    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException{
      return false;
    }
  }

  public void handleSessionStarted() throws RemoteException;

  public void handleSessionAborted(int arg1) throws RemoteException;

  public void handleSessionTerminatedByRemote() throws RemoteException;

  public void handleMessageDeliveryStatus(String arg1, String arg2) throws RemoteException;

  public void handleReceiveMessage(InstantMessage arg1) throws RemoteException;

  public void handleImError(int arg1) throws RemoteException;

  public void handleIsComposingEvent(String arg1, boolean arg2) throws RemoteException;

  public void handleConferenceEvent(String arg1, String arg2, String arg3) throws RemoteException;

  public void handleAddParticipantSuccessful() throws RemoteException;

  public void handleAddParticipantFailed(String arg1) throws RemoteException;

}