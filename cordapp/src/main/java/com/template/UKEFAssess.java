package com.template;

import co.paralleluniverse.fibers.Suspendable;
import net.corda.core.contracts.Command;
import net.corda.core.contracts.ContractState;
import net.corda.core.contracts.StateAndRef;
import net.corda.core.flows.*;
import net.corda.core.identity.Party;
import net.corda.core.node.services.Vault;
import net.corda.core.node.services.vault.QueryCriteria;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.transactions.TransactionBuilder;
import net.corda.core.utilities.ProgressTracker;
import net.corda.core.utilities.ProgressTracker.Step;

import java.security.PublicKey;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import static net.corda.core.contracts.ContractsDSL.requireThat;

//import org.apache.log4j.Logger;

public class UKEFAssess {

    @InitiatingFlow
    @StartableByRPC
    public static class Initiator extends FlowLogic<SignedTransaction>{

        private final String bondID;
        /* the node running the flow is the UKEF (it signs the transaction) */
        private final Party exporter;
        private final Party bank;
        private String UKEFSupplyId;
        private boolean isUKEFSupported;


        private final Step PREPARATION = new Step("Retrieve state to amend.");
        //    private final Step GENERATING_UKEF_TRANSACTION = new Step("Generating transaction based on UKEF activity.");
        private final Step GENERATING_BANK_TRANSACTION = new Step("Creating UKEF transaction.");
        private final Step SIGNING_TRANSACTION = new Step("Signing transaction with our private key.");
        private final Step GATHERING_SIGS = new Step("Gathering the counterparty's signature.") {
            @Override
            public ProgressTracker childProgressTracker() {
                return CollectSignaturesFlow.Companion.tracker();
            }
        };
        private final Step FINALISING_TRANSACTION = new Step("Obtaining notary signature and recording transaction.") {
            @Override
            public ProgressTracker childProgressTracker() {
                return FinalityFlow.Companion.tracker();
            }
        };


        private final ProgressTracker progressTracker = new ProgressTracker(
                GENERATING_BANK_TRANSACTION,
                PREPARATION,
                SIGNING_TRANSACTION,
                GATHERING_SIGS,
                FINALISING_TRANSACTION
        );


        /**
         *
         * @param bondID                Id of the bond id to be processed
         * @param UKEFSupplyContractID  UUID created internally by the UKEF
         * @param isUKEFSupported       UKEF decision on the bond
         * @param exporter              contractual party
         * @param bank                  contractual party
         */
        public Initiator(String bondID, String UKEFSupplyContractID, boolean isUKEFSupported, Party exporter, Party bank){
            this.bondID = bondID;
            this.exporter = exporter;
            this.bank = bank;
            this.UKEFSupplyId = UKEFSupplyContractID;
            this.isUKEFSupported = isUKEFSupported;
        }

        @Override
        public ProgressTracker getProgressTracker () {
            return progressTracker;
        }

        @Suspendable
        @Override
        public SignedTransaction call () throws FlowException {


            //Notary for the transaction
            final Party notary = getServiceHub().getNetworkMapCache().getNotaryIdentities().get(0);

            //Stage1 - generate transaction
            progressTracker.setCurrentStep(PREPARATION);

            //gather previous state to amend
            StateAndRef<UKTFBondState> inputState = getUKTFBond(this.bondID);
            UKTFBondState inputBond = inputState.getState().getData();

            if (!inputBond.getUkef().equals(getOurIdentity())) {
                throw new FlowException("Supporting a bank assessed bond can only be done by the UKEF reported in the bond");
            }

            Bond updBond = new Bond(inputBond.getBond(), this.UKEFSupplyId, this.isUKEFSupported);
            UKTFBondState outputBond = inputBond.copy(updBond);

            // Stage 2 - verifying trx
            progressTracker.setCurrentStep(GENERATING_BANK_TRANSACTION);

            List<PublicKey> requiredSigners =  Arrays.asList(getOurIdentity().getOwningKey(), exporter.getOwningKey(), bank.getOwningKey());
            final Command<UKTFContract.Commands.UKEFAssess> cmd = new Command<>(new UKTFContract.Commands.UKEFAssess(), requiredSigners);
            final TransactionBuilder txBuilder = new TransactionBuilder(notary)
                    .addInputState(inputState)
                    .addOutputState(outputBond, UKTFContract.UKTF_CONTRACT_ID)
                    .addCommand(cmd)
                    ;

            txBuilder.verify(getServiceHub());

            // Stage 3 - signing trx
            progressTracker.setCurrentStep(SIGNING_TRANSACTION);
            final SignedTransaction partSignedTx = getServiceHub().signInitialTransaction(txBuilder);


            //Step 4 - gathering trx signs
            progressTracker.setCurrentStep(GATHERING_SIGS);

            //bank & ukef signatures
            FlowSession exporterSession = initiateFlow(exporter);
            FlowSession bankSession = initiateFlow(bank);
            SignedTransaction fullySignedTx = subFlow(new CollectSignaturesFlow(
                    partSignedTx,  Arrays.asList(exporterSession, bankSession), CollectSignaturesFlow.tracker()));


            //Step 5 - finalising
            progressTracker.setCurrentStep(FINALISING_TRANSACTION);

            return subFlow(new FinalityFlow(fullySignedTx));
        }


         StateAndRef<UKTFBondState> getUKTFBond (String bondID) throws FlowException {
          //  Logger logger = Logger.getLogger("BankAssess");
            //List<StateAndRef<UKTFBond>> bonds = getServiceHub().getVaultService().queryBy(UKTFBond.class, criteria).getStates();
            QueryCriteria.VaultQueryCriteria criteria = new QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED);
            Vault.Page<UKTFBondState> results = getServiceHub().getVaultService().queryBy(UKTFBondState.class, criteria);
            List<StateAndRef<UKTFBondState>> bonds = results.getStates();

          // logger.info("Number of UNCONSUMED bonds " +  bonds.toArray().length);

          //  logger.info("Bond id to find " + bondID);

            Iterator<StateAndRef<UKTFBondState>> i = bonds.iterator();
            while (i.hasNext()) {
                StateAndRef<UKTFBondState> state = i.next();
                if (state.getState().getData().getBondID().equals(bondID)) {
          //          logger.info("found state");
                    return state;
                }
            }
            throw new FlowException(String.format("Bond with id %s not found", bondID));
        }

    }

    @InitiatedBy(Initiator.class)
    public static class UKEFAssessResponder extends FlowLogic<SignedTransaction> {

        private FlowSession ukef;

        public UKEFAssessResponder(FlowSession ukef) {
            this.ukef = ukef;
        }

        @Suspendable
        @Override
        public SignedTransaction call() throws FlowException {

            class SignExpTxFlow extends SignTransactionFlow {

                private SignExpTxFlow(FlowSession bank, ProgressTracker progressTracker) {
                    super(bank, progressTracker);
                }

                @Override
                protected void checkTransaction(SignedTransaction stx) {
                    requireThat(require -> {
                        int sizeInputs = stx.getTx().getInputs().size();
                        require.using("There should be a Bond input",sizeInputs == 1);
                        ContractState output = stx.getTx().getOutputs().get(0).getData();
                        require.using("This must be an UKTF transaction.", output instanceof UKTFBondState);
                        UKTFBondState bond = (UKTFBondState) output;
                        require.using("The UKEF support can only be equal to or less than 80% of the bond value.", (bond.getRequestedUKEFsupport() * 100 / bond.getBondValue()) <= 80 );
                        return null;
                    });
                }
            }


            return subFlow(new SignExpTxFlow(ukef, SignTransactionFlow.Companion.tracker()));
        }
    }

}
