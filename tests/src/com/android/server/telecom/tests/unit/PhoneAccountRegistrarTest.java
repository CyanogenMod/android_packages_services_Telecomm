/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.telecom.tests.unit;

import com.android.internal.util.FastXmlSerializer;
import com.android.server.telecom.Log;
import com.android.server.telecom.PhoneAccountRegistrar;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.test.AndroidTestCase;
import android.util.Xml;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Arrays;

public class PhoneAccountRegistrarTest extends AndroidTestCase {

    private static final String FILE_NAME = "phone-account-registrar-test.xml";
    private PhoneAccountRegistrar mRegistrar;

    @Override
    public void setUp() {
        mRegistrar = new PhoneAccountRegistrar(getContext(), FILE_NAME);
        mRegistrar.registerPhoneAccount(PhoneAccount.builder(
                new PhoneAccountHandle(new ComponentName("pkg0", "cls0"), "id0"),
                "label0")
                .setAddress(Uri.parse("tel:555-1212"))
                .setSubscriptionAddress(Uri.parse("tel:555-1212"))
                .setCapabilities(PhoneAccount.CAPABILITY_CONNECTION_MANAGER)
                .setIconResId(0)
                .setShortDescription("desc0")
                .build());
        mRegistrar.registerPhoneAccount(PhoneAccount.builder(
                new PhoneAccountHandle(new ComponentName("pkg0", "cls0"), "id1"),
                "label1")
                .setAddress(Uri.parse("tel:555-1212"))
                .setSubscriptionAddress(Uri.parse("tel:555-1212"))
                .setCapabilities(
                        PhoneAccount.CAPABILITY_CALL_PROVIDER
                                | PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION
                )
                .setIconResId(0)
                .setShortDescription("desc1")
                .build());
        mRegistrar.registerPhoneAccount(PhoneAccount.builder(
                new PhoneAccountHandle(new ComponentName("pkg1", "cls1"), "id2"),
                "label2")
                .setAddress(Uri.parse("tel:555-1212"))
                .setSubscriptionAddress(Uri.parse("tel:555-1212"))
                .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                .setIconResId(0)
                .setShortDescription("desc2")
                .build());
        mRegistrar.registerPhoneAccount(PhoneAccount.builder(
                new PhoneAccountHandle(new ComponentName("sippkg", "sipcls"), "id4"),
                "label2")
                .setAddress(Uri.parse("sip:test@sip.com"))
                .setSubscriptionAddress(Uri.parse("test"))
                .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                .setIconResId(0)
                .setShortDescription("desc2")
                .setSupportedUriSchemes(Arrays.asList(PhoneAccount.SCHEME_TEL))
                .build());
        mRegistrar.registerPhoneAccount(PhoneAccount.builder(
                new PhoneAccountHandle(new ComponentName("sippkg", "sipcls"), "id4"),
                "label2")
                .setAddress(Uri.parse("sip:test@sip.com"))
                .setSubscriptionAddress(Uri.parse("test"))
                .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                .setIconResId(0)
                .setShortDescription("desc2")
                .setSupportedUriSchemes(Arrays.asList(PhoneAccount.SCHEME_TEL))
                .build());
    }

    @Override
    public void tearDown() {
        mRegistrar = null;
        new File(getContext().getFilesDir(), FILE_NAME).delete();
    }

    private static <T> T roundTrip(
            Object self,
            T input,
            PhoneAccountRegistrar.XmlSerialization<T> xml,
            Context context)
            throws Exception {
        Log.d(self, "Input = %s", input);

        byte[] data;
        {
            XmlSerializer serializer = new FastXmlSerializer();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            serializer.setOutput(new BufferedOutputStream(baos), "utf-8");
            xml.writeToXml(input, serializer);
            serializer.flush();
            data = baos.toByteArray();
        }

        Log.d(self, "====== XML data ======\n%s", new String(data));

        T result = null;
        {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(new BufferedInputStream(new ByteArrayInputStream(data)), null);
            parser.nextTag();
            result = xml.readFromXml(parser, 0, context);
        }

        Log.d(self, "result = " + result);

        return result;
    }

    private void assertPhoneAccountHandleEquals(PhoneAccountHandle a, PhoneAccountHandle b) {
        if (a != b) {
            assertEquals(
                    a.getComponentName().getPackageName(),
                    b.getComponentName().getPackageName());
            assertEquals(
                    a.getComponentName().getClassName(),
                    b.getComponentName().getClassName());
            assertEquals(a.getId(), b.getId());
        }
    }

    public void testPhoneAccountHandle() throws Exception {
        PhoneAccountHandle input = new PhoneAccountHandle(new ComponentName("pkg0", "cls0"), "id0");
        PhoneAccountHandle result = roundTrip(this, input,
                PhoneAccountRegistrar.sPhoneAccountHandleXml, mContext);
        assertPhoneAccountHandleEquals(input, result);

        PhoneAccountHandle inputN = new PhoneAccountHandle(new ComponentName("pkg0", "cls0"), null);
        PhoneAccountHandle resultN = roundTrip(this, inputN,
                PhoneAccountRegistrar.sPhoneAccountHandleXml, mContext);
        Log.i(this, "inputN = %s, resultN = %s", inputN, resultN);
        assertPhoneAccountHandleEquals(inputN, resultN);
    }

    private void assertPhoneAccountEquals(PhoneAccount a, PhoneAccount b) {
        if (a != b) {
            assertPhoneAccountHandleEquals(a.getAccountHandle(), b.getAccountHandle());
            assertEquals(a.getAddress(), b.getAddress());
            assertEquals(a.getSubscriptionAddress(), b.getSubscriptionAddress());
            assertEquals(a.getCapabilities(), b.getCapabilities());
            assertEquals(a.getIconResId(), b.getIconResId());
            assertEquals(a.getLabel(), b.getLabel());
            assertEquals(a.getShortDescription(), b.getShortDescription());
            assertEquals(a.getSupportedUriSchemes(), b.getSupportedUriSchemes());
        }
    }

    public void testPhoneAccount() throws Exception {
        PhoneAccount input = makeQuickAccount("pkg0", "cls0", "id0", 0);
        PhoneAccount result = roundTrip(this, input, PhoneAccountRegistrar.sPhoneAccountXml,
                mContext);
        assertPhoneAccountEquals(input, result);

        PhoneAccountHandle handleN =
            new PhoneAccountHandle(new ComponentName("pkg0", "cls0"), null);
        PhoneAccount inputN = PhoneAccount.builder(handleN, "label").build();
        PhoneAccount resultN = roundTrip(this, inputN, PhoneAccountRegistrar.sPhoneAccountXml,
                mContext);
        assertPhoneAccountEquals(inputN, resultN);
    }

    private void assertStateEquals(PhoneAccountRegistrar.State a, PhoneAccountRegistrar.State b) {
        assertPhoneAccountHandleEquals(a.defaultOutgoing, b.defaultOutgoing);
        assertPhoneAccountHandleEquals(a.simCallManager, b.simCallManager);
        assertEquals(a.accounts.size(), b.accounts.size());
        for (int i = 0; i < a.accounts.size(); i++) {
            assertPhoneAccountEquals(a.accounts.get(i), b.accounts.get(i));
        }
    }

    public void testState() throws Exception {
        PhoneAccountRegistrar.State input = makeQuickState();
        PhoneAccountRegistrar.State result = roundTrip(this, input, PhoneAccountRegistrar.sStateXml,
                mContext);
        assertStateEquals(input, result);
    }

    public void testAccounts() throws Exception {
        assertEquals(4, mRegistrar.getAllPhoneAccountHandles().size());
        assertEquals(3, mRegistrar.getCallCapablePhoneAccounts().size());
        assertEquals(null, mRegistrar.getSimCallManager());
        assertEquals(null, mRegistrar.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL));
    }

    public void testSimCallManager() throws Exception {
        // Establish initial conditions
        assertEquals(null, mRegistrar.getSimCallManager());
        PhoneAccountHandle h = new PhoneAccountHandle(new ComponentName("pkg0", "cls0"), "id0");
        mRegistrar.setSimCallManager(h);
        assertPhoneAccountHandleEquals(h, mRegistrar.getSimCallManager());
        mRegistrar.unregisterPhoneAccount(h);
        // If account is un-registered, querying returns null
        assertEquals(null, mRegistrar.getSimCallManager());
        // But if account is re-registered, setting comes back
        mRegistrar.registerPhoneAccount(makeQuickAccount("pkg0", "cls0", "id0", 99));
        assertPhoneAccountHandleEquals(h, mRegistrar.getSimCallManager());
        // De-register by setting to null
        mRegistrar.setSimCallManager(null);
        assertEquals(null, mRegistrar.getSimCallManager());
        // If argument not have SIM_CALL_MANAGER capability, this is a no-op
        mRegistrar.setSimCallManager(
                new PhoneAccountHandle(new ComponentName("pkg0", "cls0"), "id1"));
        assertEquals(null, mRegistrar.getSimCallManager());
    }

    public void testDefaultOutgoing() {
        // Establish initial conditions
        assertEquals(null, mRegistrar.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL));
        PhoneAccountHandle h = new PhoneAccountHandle(new ComponentName("pkg0", "cls0"), "id1");
        mRegistrar.setUserSelectedOutgoingPhoneAccount(h);
        assertPhoneAccountHandleEquals(h, mRegistrar.getDefaultOutgoingPhoneAccount(
                        PhoneAccount.SCHEME_TEL));
        // If account is un-registered, querying returns null
        mRegistrar.unregisterPhoneAccount(h);
        assertEquals(null, mRegistrar.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL));
        // But if account is re-registered, setting comes back
        mRegistrar.registerPhoneAccount(makeQuickAccount("pkg0", "cls0", "id1", 99));
        assertPhoneAccountHandleEquals(h, mRegistrar.getDefaultOutgoingPhoneAccount(
                        PhoneAccount.SCHEME_TEL));
        // De-register by setting to null
        mRegistrar.setUserSelectedOutgoingPhoneAccount(null);
        assertEquals(null, mRegistrar.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL));
        // If argument not have CALL_PROVIDER capability, this is a no-op
        mRegistrar.setUserSelectedOutgoingPhoneAccount(
                new PhoneAccountHandle(new ComponentName("pkg0", "cls0"), "id0"));
        assertEquals(null, mRegistrar.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL));
        // If only have one account, it is the default
        mRegistrar.unregisterPhoneAccount(
                new PhoneAccountHandle(new ComponentName("pkg0", "cls0"), "id0"));
        mRegistrar.unregisterPhoneAccount(
                new PhoneAccountHandle(new ComponentName("pkg0", "cls0"), "id1"));
        mRegistrar.unregisterPhoneAccount(
                new PhoneAccountHandle(new ComponentName("pkg1", "cls1"), "id2"));
        assertPhoneAccountHandleEquals(
                new PhoneAccountHandle(new ComponentName("pkg1", "cls1"), "id3"),
                mRegistrar.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL));
        // If have one account but not suitable, default returns null
        mRegistrar.unregisterPhoneAccount(
                new PhoneAccountHandle(new ComponentName("pkg1", "cls1"), "id3"));
        mRegistrar.registerPhoneAccount(PhoneAccount.builder(
                new PhoneAccountHandle(new ComponentName("pkg0", "cls0"), "id0"),
                "label0")
                .setAddress(Uri.parse("tel:555-1212"))
                .setSubscriptionAddress(Uri.parse("tel:555-1212"))
                .setCapabilities(PhoneAccount.CAPABILITY_CONNECTION_MANAGER)
                .setIconResId(0)
                .setShortDescription("desc0")
                .build());
        assertEquals(null, mRegistrar.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL));
    }

    private static PhoneAccount makeQuickAccount(String pkg, String cls, String id, int idx) {
        return PhoneAccount.builder(
                new PhoneAccountHandle(new ComponentName(pkg, cls), id),
                "label" + idx)
                .setAddress(Uri.parse("http://foo.com/" + idx))
                .setSubscriptionAddress(Uri.parse("tel:555-000" + idx))
                .setCapabilities(idx)
                .setIconResId(idx)
                .setShortDescription("desc" + idx)
                .build();
    }

    private static PhoneAccountRegistrar.State makeQuickState() {
        PhoneAccountRegistrar.State s = new PhoneAccountRegistrar.State();
        s.accounts.add(makeQuickAccount("pkg0", "cls0", "id0", 0));
        s.accounts.add(makeQuickAccount("pkg0", "cls0", "id1", 1));
        s.accounts.add(makeQuickAccount("pkg1", "cls1", "id2", 2));
        s.defaultOutgoing = new PhoneAccountHandle(new ComponentName("pkg0", "cls0"), "id0");
        s.simCallManager = new PhoneAccountHandle(new ComponentName("pkg0", "cls0"), "id1");
        return s;
    }
}
