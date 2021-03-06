/*
 * This software is in the public domain under CC0 1.0 Universal plus a 
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */


import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification

import java.sql.Timestamp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/* To run these make sure moqui, and mantle are in place and run:
    "gradle cleanAll load runtime/mantle/mantle-usl:test"
   Or to quick run with saved DB copy use "gradle loadSave" once then each time "gradle reloadSave runtime/mantle/mantle-usl:test"
 */

class AssetReservationMultipleThreads extends Specification {
    @Shared
    protected final static Logger logger = LoggerFactory.getLogger(AssetReservationMultipleThreads.class)

    @Shared
    ExecutionContext gec

    @Shared
    long effectiveTime = System.currentTimeMillis()


    def setupSpec() {
        // init the framework, get the ec
        gec = Moqui.getExecutionContext()
        // set an effective date so data check works, etc
        gec.user.setEffectiveTime(new Timestamp(effectiveTime))
        gec.user.loginUser("john.doe", "moqui", null)

    }

    def cleanupSpec() {
        gec.destroy()
    }
    def setup() {
        gec.artifactExecution.disableAuthz()
    }

    def cleanup() {
        gec.artifactExecution.enableAuthz()
    }
    def "Asset reservation from multiple threads"() {
        def numThreads = 5
        def latch = new CountDownLatch(5)
        def list = []

        when:
        numThreads.times { threadId ->
            Thread.start {
                try {
                    list.add(makeOrder(threadId))
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await(30, TimeUnit.SECONDS)
        EntityValue asset = gec.entity.find("mantle.product.asset.Asset").condition("assetId","DEMO_1_1A").one()
        logger.info("ATP of DEMO_1_1A is " + asset.availableToPromiseTotal)

        EntityList resList = gec.entity.find("mantle.product.issuance.AssetReservation").condition("assetId","DEMO_1_1A").list()
        def totalNotAvailable = resList.sum {EntityValue res -> res.quantityNotAvailable}
        logger.info("AssetReservations be created are ${resList}")
        logger.info("Total not available of AssetReservations is " + totalNotAvailable)

        then:
        asset.availableToPromiseTotal == -totalNotAvailable

        // cleanup:
        // cancelOrders(list)
    }

    String makeOrder(int threadId) {
        ExecutionContext ec = Moqui.getExecutionContext()
        ec.user.setEffectiveTime(new Timestamp(effectiveTime + threadId))
        ec.user.loginUser("joe@public.com", "moqui", null)
        ec.artifactExecution.disableAuthz()

        String cartOrderId = null
        boolean beganTx = ec.transaction.begin(null)
        try {
            String productStoreId = "POPC_DEFAULT"
            EntityValue productStore = ec.entity.find("mantle.product.store.ProductStore").condition("productStoreId", productStoreId).one()
            String currencyUomId = productStore.defaultCurrencyUomId
            String customerPartyId = ec.user.userAccount.partyId

            Map addOut1 = ec.service.sync().name("mantle.order.OrderServices.add#OrderProductQuantity")
                    .parameters([productId: 'DEMO_1_1', quantity: 60, customerPartyId: customerPartyId,
                                 currencyUomId: currencyUomId, productStoreId: productStoreId]).call()

            cartOrderId = addOut1.orderId
            // String orderPartSeqId = addOut1.orderPartSeqId

            ec.service.sync().name("mantle.order.OrderServices.set#OrderBillingShippingInfo")
                    .parameters([orderId: cartOrderId, paymentMethodId:'CustJqpCc', paymentInstrumentEnumId:'PiCreditCard',
                                 shippingPostalContactMechId: 'CustJqpAddr', shippingTelecomContactMechId: 'CustJqpTeln',
                                 carrierPartyId: '_NA_', shipmentMethodEnumId: 'ShMthGround']).call()
            ec.service.sync().name("mantle.order.OrderServices.place#Order").parameters([orderId: cartOrderId]).call()

            ec.user.logoutUser()
        } finally {
            ec.transaction.commit(beganTx)
            ec.destroy()
        }

        return cartOrderId
    }

    boolean cancelOrders(List orders) {
        orders.each { orderId -> gec.service.sync().name("mantle.order.OrderServices.cancel#Order").parameters([orderId: orderId]).call() }
        true
    }
}
