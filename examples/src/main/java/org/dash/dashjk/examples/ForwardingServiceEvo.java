/*
 * Copyright 2013 Google Inc.
 * Copyright 2014 Andreas Schildbach
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dash.dashjk.examples;

import com.google.common.io.BaseEncoding;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Base58;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.KeyId;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.crypto.KeyCrypterException;
import org.bitcoinj.evolution.CreditFundingTransaction;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.net.discovery.MasternodePeerDiscovery;
import org.bitcoinj.params.DevNetParams;
import org.bitcoinj.params.EvoNetParams;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.MobileDevNetParams;
import org.bitcoinj.params.PalinkaDevNetParams;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.utils.BriefLogFormatter;
import org.bitcoinj.wallet.AuthenticationKeyChain;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.listeners.WalletCoinsReceivedEventListener;
import org.dash.dashjk.platform.Platform;
import org.dashevo.dapiclient.DapiClient;
import org.dashevo.dapiclient.SingleMasternode;
import org.dashevo.dapiclient.model.DocumentQuery;
import org.dashevo.dpp.DashPlatformProtocol;
import org.dashevo.dpp.DataProvider;
import org.dashevo.dpp.Factory;
import org.dashevo.dpp.contract.Contract;
import org.dashevo.dpp.document.Document;
import org.dashevo.dpp.identity.Identity;
import org.dashevo.dpp.identity.IdentityCreateTransition;
import org.dashevo.dpp.identity.IdentityPublicKey;
import org.dashevo.dpp.util.Cbor;
import org.dashj.bls.Utils;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.Thread.sleep;

/**
 * ForwardingService demonstrates basic usage of the library. It sits on the network and when it receives coins, simply
 * sends them onwards to an address given on the command line.
 */
public class ForwardingServiceEvo {
    private static Address forwardingAddress;
    private static WalletAppKit kit;
    private static Platform platform;

    public static void main(String[] args) throws Exception {
        // This line makes the log output more compact and easily read, especially when using the JDK log adapter.
        BriefLogFormatter.initWithSilentBitcoinJ();
        if (args.length < 1) {
            System.err.println("Usage: address-to-send-back-to [regtest|testnet|evonet|palinka|devnet] [devnet-name] [devnet-sporkaddress] [devnet-port] [devnet-dnsseed...]");
            return;
        }

        // Figure out which network we should connect to. Each one gets its own set of files.
        NetworkParameters params;
        String filePrefix;
        String checkpoints = null;
        if (args.length > 1 && args[1].equals("testnet")) {
            params = TestNet3Params.get();
            filePrefix = "forwarding-service-testnet";
            checkpoints = "checkpoints-testnet.txt";
        } else if (args.length > 1 && args[1].equals("regtest")) {
            params = RegTestParams.get();
            filePrefix = "forwarding-service-regtest";
        } else if (args.length > 1 && args[1].equals("palinka")) {
            params = PalinkaDevNetParams.get();
            filePrefix = "forwarding-service-palinka";
        } else if (args.length > 1 && args[1].equals("mobile")) {
            params = MobileDevNetParams.get();
            filePrefix = "forwarding-service-mobile";
            platform = new Platform(true);
        } else if (args.length > 1 && args[1].equals("evonet")) {
            params = EvoNetParams.get();
            filePrefix = "forwarding-service-evonet";
            platform = new Platform(false);
        } else if( args.length > 6 && args[1].equals("devnet")) {
            String [] dnsSeeds = new String[args.length - 5];
            System.arraycopy(args, 5, dnsSeeds, 0, args.length - 5);
            params = DevNetParams.get(args[2], args[3], Integer.parseInt(args[4]), dnsSeeds);
            filePrefix = "forwarding-service-devnet";
        }else {
            params = MainNetParams.get();
            filePrefix = "forwarding-service";
            checkpoints = "checkpoints.txt";
        }
        // Parse the address given as the first parameter.
        forwardingAddress = Address.fromBase58(params, args[0]);

        System.out.println("Network: " + params.getId());
        System.out.println("Forwarding address: " + forwardingAddress);

        // Start up a basic app using a class that automates some boilerplate.
        kit = new WalletAppKit(params, new File("."), filePrefix) {
            @Override
            protected void onSetupCompleted() {
                if(!kit.wallet().hasAuthenticationKeyChains())
                    kit.wallet().initializeAuthenticationKeyChains(kit.wallet().getKeyChainSeed(), null);
                kit.setDiscovery(new MasternodePeerDiscovery(kit.wallet().getContext().masternodeListManager.getListAtChainTip()));
            }
        };

        if (params == RegTestParams.get()) {
            // Regression test mode is designed for testing and development only, so there's no public network for it.
            // If you pick this mode, you're expected to be running a local "bitcoind -regtest" instance.
            kit.connectToLocalHost();
        }

        if(checkpoints != null) {
            try {
                FileInputStream checkpointStream = new FileInputStream("./" + checkpoints);
                kit.setCheckpoints(checkpointStream);
            } catch (FileNotFoundException x) {
                //swallow
            }
        }

        // Download the block chain and wait until it's done.
        kit.startAsync();
        kit.awaitRunning();

        // We want to know when we receive money.
        kit.wallet().addCoinsReceivedEventListener(new WalletCoinsReceivedEventListener() {
            @Override
            public void onCoinsReceived(Wallet w, Transaction tx, Coin prevBalance, Coin newBalance) {
                // Runs in the dedicated "user thread" (see bitcoinj docs for more info on this).
                //
                // The transaction "tx" can either be pending, or included into a block (we didn't see the broadcast).
                Coin value = tx.getValueSentToMe(w);
                System.out.println("Received tx for " + value.toFriendlyString() + ": " + tx);
                System.out.println("Transaction will be forwarded after it confirms.");
                // Wait until it's made it into the block chain (may run immediately if it's already there).
                //
                // For this dummy app of course, we could just forward the unconfirmed transaction. If it were
                // to be double spent, no harm done. Wallet.allowSpendingUnconfirmedTransactions() would have to
                // be called in onSetupCompleted() above. But we don't do that here to demonstrate the more common
                // case of waiting for a block.
                Context.propagate(w.getContext());
                Futures.addCallback(tx.getConfidence().getDepthFuture(2), new FutureCallback<TransactionConfidence>() {
                    @Override
                    public void onSuccess(TransactionConfidence result) {
                        System.out.println("Confirmation received.");
                        forwardCoins(tx);
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        // This kind of future can't fail, just rethrow in case something weird happens.
                        throw new RuntimeException(t);
                    }
                }, MoreExecutors.directExecutor());

                Futures.addCallback(tx.getConfidence().getDepthFuture(1), new FutureCallback<TransactionConfidence>() {
                    @Override
                    public void onSuccess(TransactionConfidence result) {
                        System.out.println("Confirmation received.");
                        blockchainIdentity(tx);
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        // This kind of future can't fail, just rethrow in case something weird happens.
                        throw new RuntimeException(t);
                    }
                }, MoreExecutors.directExecutor());

                /*Futures.addCallback(tx.getConfidence().getDepthFuture(3), new FutureCallback<TransactionConfidence>() {
                    @Override
                    public void onSuccess(TransactionConfidence result) {
                        System.out.println("3 confirmations received. -- create user");
                        blockchainUser(tx);
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        // This kind of future can't fail, just rethrow in case something weird happens.
                        throw new RuntimeException(t);
                    }
                }, MoreExecutors.directExecutor());*/
            }
        });

        Address sendToAddress = Address.fromKey(params, kit.wallet().currentReceiveKey());
        System.out.println("Send coins to: " + sendToAddress);
        System.out.println("Waiting for coins to arrive. Press Ctrl-C to quit.");

        //get this:
        String id = "J2jWLKNWogVf1B8fdo6rxMeQgDWWi9aGd9JPcTHxNj7H";
        //apiClient client = new DapiClient(EvoNetParams.MASTERNODES[1], true);
        //client.getIdentity(id);
        //client.shutdown();

        //System.out.println(kit.wallet().toString(true, true, null, true, true, null)/*.getBlockchainIdentityKeyChain()*/);
        //System.out.println("devnet block:" + kit.wallet().getParams().getDevNetGenesisBlock().toString());
        List<CreditFundingTransaction> list = kit.wallet().getCreditFundingTransactions();
        for(CreditFundingTransaction tx : list) {
            System.out.println(tx.getTxId());
            String identityId = tx.getCreditBurnIdentityIdentifier().toStringBase58();
            System.out.println("  id: " + identityId);
            Identity identity = platform.getIdentities().get(identityId);
            if(identity == null)
                identity = platform.getIdentities().get(BaseEncoding.base64().omitPadding().encode(tx.getCreditBurnIdentityIdentifier().getBytes()));
            if(identity != null) {
                System.out.println("  id json: " + identity.toJSON().toString());
                try {
                    DocumentQuery options = new DocumentQuery(Arrays.asList(Arrays.asList("$userId", "==", identityId)), null, 5, 0, 0);
                    List<Document> documents = platform.getDocuments().get("domain", options);
                    if (documents != null & documents.size() > 0) {
                        System.out.println("  name: " + documents.get(0).getData().get("normalizedName"));
                    } else {
                        System.out.println("  no names found");
                    }
                } catch (Exception x) {
                    System.out.println("  no names found");
                }
            }
        }

        System.out.println("------------------------------------\nNames found starting with hashengineering");
        DocumentQuery options = new DocumentQuery(Arrays.asList(
                Arrays.asList("normalizedLabel", "startsWith", "hashengineering"),
                Arrays.asList("normalizedParentDomainName", "==", "dash")), null, 100, 0, 0);
        try {
            List<Document> documents = platform.getDocuments().get("dpns.domain", options);
            for(Document document : documents) {
                System.out.println(document.getData().get("$userId") + "->  name: " + document.getData().get("normalizedLabel"));
            }
            System.out.println(documents.size() + " names found");
        } catch(Exception e) {
            System.out.println(e);
        }



        try {
            sleep(Long.MAX_VALUE);
        } catch (InterruptedException ignored) {}
    }

    static CreditFundingTransaction lastTx = null;

    private static void forwardCoins(Transaction tx) {
        try {
            if(CreditFundingTransaction.isCreditFundingTransaction(tx))
                return;
            // Now send the coins onwards.
            SendRequest sendRequest = SendRequest.emptyWallet(forwardingAddress);
            Wallet.SendResult sendResult = kit.wallet().sendCoins(sendRequest);
            checkNotNull(sendResult);  // We should never try to send more coins than we have!
            System.out.println("Sending ...");
            // Register a callback that is invoked when the transaction has propagated across the network.
            // This shows a second style of registering ListenableFuture callbacks, it works when you don't
            // need access to the object the future returns.
            sendResult.broadcastComplete.addListener(new Runnable() {
                @Override
                public void run() {
                    // The wallet has changed now, it'll get auto saved shortly or when the app shuts down.
                    System.out.println("Sent coins onwards! Transaction hash is " + sendResult.tx.getTxId());
                }
            }, MoreExecutors.directExecutor());

            System.out.println("Creating identity");

            //this is a base64 id, which is not used by dapi-client
            lastIdentityId = platform.getIdentities().register(Identity.IdentityType.USER, lastTx);
            System.out.println("Identity created: " + lastIdentityId);
            //this is the base58 id
            lastIdentityId = lastTx.getCreditBurnIdentityIdentifier().toStringBase58();

            System.out.println("Identity created: " + lastTx.getCreditBurnIdentityIdentifier().toStringBase58());
            /*DashPlatformProtocol dpp = new DashPlatformProtocol(dataProvider);

            kit.wallet().getBlockchainIdentityFundingKeyChain().getKeyByPubKeyHash(lastTx.getCreditBurnPublicKeyId().getBytes());
            IdentityPublicKey identityPublicKey = new IdentityPublicKey(lastTx.getUsedDerivationPathIndex()+1,
                    IdentityPublicKey.TYPES.ECDSA_SECP256K1, Base64.toBase64String(lastTx.getCreditBurnPublicKey().getPubKey()), true);
            List<IdentityPublicKey> keyList = new ArrayList<>();
            keyList.add(identityPublicKey);
            Identity identity = dpp.identity.create(Base58.encode(lastTx.getCreditBurnIdentityIdentifier().getBytes()), Identity.IdentityType.USER,
                    keyList);
            IdentityCreateTransition st = new IdentityCreateTransition(Identity.IdentityType.USER,
                    lastTx.getLockedOutpoint().toStringBase64(), keyList, 0);

            st.sign(identityPublicKey, Utils.HEX.encode(lastTx.getCreditBurnPublicKey().getPrivKeyBytes()));

            DapiClient client = new DapiClient(EvoNetParams.MASTERNODES[1], true);
            client.applyStateTransition(st);
            client.shutdown();
            lastIdentityId = lastTx.getCreditBurnIdentityIdentifier().toStringBase58();
            System.out.println("Identity created: " + lastIdentityId);
*/
            sleep(30*1000);
            blockchainUser(lastTx);

        } catch (KeyCrypterException | InsufficientMoneyException | InterruptedException e) {
            // We don't use encrypted wallets in this example - can never happen.
            throw new RuntimeException(e);
        }
    }

    static Identity lastIdentity = null;
    static String lastIdentityId = null;

    private static void blockchainIdentity(Transaction tx) {
        try {
            // Now send the coins onwards.

            if(CreditFundingTransaction.isCreditFundingTransaction(tx))
                return;

            AuthenticationKeyChain blockchainIdentityFunding = kit.wallet().getBlockchainIdentityFundingKeyChain();
            ECKey publicKey = blockchainIdentityFunding.freshAuthenticationKey();
            Coin fundingAmount = Coin.valueOf(40000);
            SendRequest sendRequest = SendRequest.creditFundingTransaction(kit.params(), publicKey, fundingAmount);
            Wallet.SendResult sendResult = kit.wallet().sendCoins(sendRequest);
            System.out.println("Sending Credit Funding Transaction...");
            sendResult.broadcastComplete.addListener(new Runnable() {
                @Override
                public void run() {
                    // The wallet has changed now, it'll get auto saved shortly or when the app shuts down.
                    System.out.println("Blockchain Identity Funding Transaction hash is " + sendResult.tx.getTxId());
                    System.out.println(sendResult.tx.toString());
                }
            }, MoreExecutors.directExecutor());

            lastTx = (CreditFundingTransaction)sendResult.tx;

        } catch (KeyCrypterException | InsufficientMoneyException e) {
            // We don't use encrypted wallets in this example - can never happen.
            throw new RuntimeException(e);
        }
    }

    private static void blockchainUser(Transaction tx) {
        try {
            // Now send the coins onwards.

            //if(!CreditFundingTransaction.isCreditFundingTransaction(tx))
            //    return;

            AuthenticationKeyChain blockchainIdentityFunding = kit.wallet().getBlockchainIdentityFundingKeyChain();
            ECKey publicKey = blockchainIdentityFunding.currentAuthenticationKey();
            //if(!Base64.decode(lastIdentity.getPublicKeys().get(0).getData()).equals(publicKey.getPubKey()))
            //    return;

            Identity identity = platform.getIdentities().get(lastIdentityId);
            if(identity != null)
                System.out.println("identity requested: " + identity.toJSON());
            else System.out.println("failed to get identity:" + lastIdentityId);

            String name = "hashengineering-"+ new Random().nextInt();
            System.out.println("Registering name:" + name + " for identity: " + identity.getId());
            platform.getNames().register(name, identity,
                    kit.wallet().getBlockchainIdentityFundingKeyChain().currentAuthenticationKey());


            Document nameDocument = platform.getNames().get(name);
            System.out.println("name: " + nameDocument.getData().get("normalizedLabel") +"->" + nameDocument.toJSON());

        } catch (KeyCrypterException e) {
            // We don't use encrypted wallets in this example - can never happen.
            throw new RuntimeException(e);
        }
    }

    static DataProvider dataProvider = new DataProvider() {
        @NotNull
        @Override
        public Contract fetchDataContract(@NotNull String s) {
            return null;
        }

        @NotNull
        @Override
        public List<Document> fetchDocuments(@NotNull String s, @NotNull String s1, @NotNull Object o) {
            return null;
        }

        @Override
        public int fetchTransaction(@NotNull String s) {
            return 0;
        }

        @NotNull
        @Override
        public Identity fetchIdentity(@NotNull String s) {
            return null;
        }
    };


}