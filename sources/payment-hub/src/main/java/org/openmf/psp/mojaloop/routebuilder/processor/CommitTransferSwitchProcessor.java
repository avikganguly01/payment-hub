/*
 * This Source Code Form is subject to the terms of the Mozilla
 * Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at
 *
 *  https://mozilla.org/MPL/2.0/.
 */
package org.openmf.psp.mojaloop.routebuilder.processor;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.openmf.psp.config.SwitchSettings;
import org.openmf.psp.dto.MoneyData;
import org.openmf.psp.internal.FspId;
import org.openmf.psp.mojaloop.cache.TransactionContextHolder;
import org.openmf.psp.mojaloop.component.SwitchRestClient;
import org.openmf.psp.mojaloop.constant.ExchangeHeader;
import org.openmf.psp.mojaloop.dto.mojaloop.TransferSwitchRequestDTO;
import org.openmf.psp.mojaloop.internal.Ilp;
import org.openmf.psp.mojaloop.internal.TransactionCacheContext;
import org.openmf.psp.mojaloop.internal.TransactionRoleContext;
import org.openmf.psp.type.TransactionRole;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Processor to send POST /transfers COMMIT request to the other side FSP through interoperable switch.
 */
@Component("commitTransferSwitchProcessor")
public class CommitTransferSwitchProcessor implements Processor {

    private SwitchRestClient switchRestClient;

    private TransactionContextHolder transactionContextHolder;

    private SwitchSettings switchSettings;

    private CommitTransferFspProcessor fspProcessor;

    @Autowired
    public CommitTransferSwitchProcessor(SwitchRestClient switchRestClient, TransactionContextHolder transactionContextHolder,
                                         SwitchSettings switchSettings, CommitTransferFspProcessor commitTransferFspProcessor) {
        this.switchRestClient = switchRestClient;
        this.transactionContextHolder = transactionContextHolder;
        this.switchSettings = switchSettings;
        this.fspProcessor = commitTransferFspProcessor;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        if(switchSettings.isIntegrationEnabled()) {
            callSwitch(exchange);
        } else {
            callFspDirectly(exchange);
        }
    }

    private void callSwitch(Exchange exchange) {
        String transactionId = exchange.getProperty(ExchangeHeader.TRANSACTION_ID.getKey(), String.class);
        TransactionCacheContext transactionContext = transactionContextHolder.getTransactionContext(transactionId);

        TransactionRole currentRole = exchange.getProperty(ExchangeHeader.CURRENT_ROLE.getKey(), TransactionRole.class);
        TransactionRoleContext sourceContext = transactionContext.getRoleContext(currentRole);

        TransactionRole transferRole = exchange.getProperty(ExchangeHeader.TRANSFER_ROLE.getKey(), TransactionRole.class);
        TransactionRoleContext destContext = transactionContext.getRoleContext(transferRole);

        String transferId = transactionContextHolder.getOrCreateTransferId(transactionId);

        MoneyData amount = new MoneyData(transactionContext.getTransferAmount(), transactionContext.getCurrency());
        String payerFsp = transactionContext.getRoleContext(TransactionRole.PAYER).getFspId().getId();
        String payeeFsp = transactionContext.getRoleContext(TransactionRole.PAYEE).getFspId().getId();
        Ilp ilp = transactionContext.getIlp();
        TransferSwitchRequestDTO request = new TransferSwitchRequestDTO(transferId, payerFsp, payeeFsp, amount, ilp.getPacket(),
                ilp.getCondition(), transactionContext.getExpiration(), transactionContext.getExtensionList());

        switchRestClient.callPostTransferCommit(request, sourceContext.getFspId(), destContext.getFspId());
    }

    private void callFspDirectly(Exchange exchange) throws Exception {
        FspId currentFsp = exchange.getProperty(ExchangeHeader.CURRENT_FSP_ID.getKey(), FspId.class);
        FspId payeeFsp = exchange.getProperty(ExchangeHeader.PAYEE_FSP_ID.getKey(), FspId.class);

        if (payeeFsp == null || !payeeFsp.getInstance().equals(currentFsp.getInstance()))
            throw new RuntimeException("Payee is on another instance, can not commit transaction without switch");

        fspProcessor.process(exchange);
    }
}
