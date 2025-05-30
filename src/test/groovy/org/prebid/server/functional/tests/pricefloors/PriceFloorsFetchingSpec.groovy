package org.prebid.server.functional.tests.pricefloors

import org.prebid.server.functional.model.config.PriceFloorsFetch
import org.prebid.server.functional.model.db.StoredRequest
import org.prebid.server.functional.model.pricefloors.FloorModelGroup
import org.prebid.server.functional.model.pricefloors.PriceFloorData
import org.prebid.server.functional.model.pricefloors.Rule
import org.prebid.server.functional.model.request.amp.AmpRequest
import org.prebid.server.functional.model.request.auction.BidRequest
import org.prebid.server.functional.model.request.auction.ExtPrebidFloors
import org.prebid.server.functional.model.request.auction.PrebidStoredRequest
import org.prebid.server.functional.model.response.auction.BidResponse
import org.prebid.server.functional.util.PBSUtils

import java.time.Instant

import static org.mockserver.model.HttpStatusCode.BAD_REQUEST_400
import static org.prebid.server.functional.model.Currency.EUR
import static org.prebid.server.functional.model.Currency.JPY
import static org.prebid.server.functional.model.bidder.BidderName.GENERIC
import static org.prebid.server.functional.model.pricefloors.Country.MULTIPLE
import static org.prebid.server.functional.model.pricefloors.MediaType.BANNER
import static org.prebid.server.functional.model.request.auction.DistributionChannel.APP
import static org.prebid.server.functional.model.request.auction.DistributionChannel.SITE
import static org.prebid.server.functional.model.request.auction.FetchStatus.ERROR
import static org.prebid.server.functional.model.request.auction.FetchStatus.INPROGRESS
import static org.prebid.server.functional.model.request.auction.FetchStatus.NONE
import static org.prebid.server.functional.model.request.auction.FetchStatus.SUCCESS
import static org.prebid.server.functional.model.request.auction.Location.FETCH
import static org.prebid.server.functional.model.request.auction.Location.NO_DATA
import static org.prebid.server.functional.model.request.auction.Location.REQUEST
import static org.prebid.server.functional.model.response.auction.ErrorType.PREBID

class PriceFloorsFetchingSpec extends PriceFloorsBaseSpec {

    private static final int ENFORCE_FLOORS_RATE_MAX = 100
    private static final int DEFAULT_MAX_AGE_SEC = 600
    private static final int DEFAULT_PERIOD_SEC = 300
    private static final int TIMEOUT_MS_MIN = 10
    private static final int TIMEOUT_MS_MAX = 10000
    private static final int SKIP_RATE_MIN = 0
    private static final int SKIP_RATE_MAX = 100
    private static final int USE_FETCH_DATA_RATE_MIN = 0
    private static final int USE_FETCH_DATA_RATE_MAX = 100
    private static final int DEFAULT_FLOOR_VALUE_MIN = 0
    private static final int FLOOR_MIN = 0

    private static final String FETCH_FAILURE_METRIC = "price-floors.fetch.failure"
    private static final String PRICE_FLOOR_VALUES_MISSING = 'Price floor rules values can\'t be null or empty, but were null'
    private static final String MODEL_WEIGHT_INVALID = "Price floor modelGroup modelWeight must be in range(1-100), but was %s"
    private static final String SKIP_RATE_INVALID = "Price floor modelGroup skipRate must be in range(0-100), but was %s"
    private static Instant startTime

    def setupSpec() {
        startTime = Instant.now()
    }

    def "PBS should activate floors feature when price-floors.enabled = true in PBS config"() {
        given: "Pbs with PF configuration"
        def pbsService = pbsServiceFactory.getService(FLOORS_CONFIG + ["price-floors.enabled": "true"])

        and: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account with enabled fetch and fetch.url in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.accountId)
        accountDao.save(account)

        and: "PBS fetch rules from floors provider"
        cacheFloorsProviderRules(bidRequest, pbsService)

        when: "PBS processes auction request"
        pbsService.sendAuctionRequest(bidRequest)

        then: "PBS should fetch data"
        assert floorsProvider.getRequestCount(bidRequest.accountId) == 1

        and: "PBS should signal bids"
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last()
        assert bidderRequest.imp[0].bidFloor
    }

    def "PBS should not activate floors feature when price-floors.enabled = false in #description config"() {
        given: "Pbs with PF configuration"
        def pbsService = pbsServiceFactory.getService(FLOORS_CONFIG + ["price-floors.enabled": pbdConfigEnabled])

        and: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account with enabled fetch and fetch.url in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.accountId).tap {
            config.auction.priceFloors.enabled = accountConfigEnabled
        }
        accountDao.save(account)

        and: "PBS fetch rules from floors provider"
        cacheFloorsProviderRules(bidRequest, pbsService)

        when: "PBS processes auction request"
        pbsService.sendAuctionRequest(bidRequest)

        then: "PBS should no fetching, no signaling, no enforcing"
        assert floorsProvider.getRequestCount(bidRequest.accountId) == 0
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last()
        assert !bidderRequest.imp[0].bidFloor

        where:
        description | pbdConfigEnabled | accountConfigEnabled
        "PBS"       | "false"          | true
        "account"   | "true"           | false
    }

    def "PBS should validate fetch.url from account config"() {
        given: "Test start time"
        def startTime = Instant.now()

        and: "Default BidRequest"
        def bidRequest = BidRequest.defaultBidRequest

        and: "Account with enabled fetch, without fetch.url in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.accountId).tap {
            config.auction.priceFloors.fetch.url = null
        }
        accountDao.save(account)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        then: "PBS should log error"
        def logs = floorsPbsService.getLogsByTime(startTime)
        def floorsLogs = getLogsByText(logs, bidRequest.accountId)
        assert floorsLogs.size() == 1
        assert floorsLogs.first().contains("alformed fetch.url: 'null' passed for account $bidRequest.accountId")

        and: "PBS floors validation failure should not reject the entire auction"
        assert !response.seatbid.isEmpty()

        and: "PBS should fall back to the startup configuration"
        def bidderRequest = bidder.getBidderRequest(bidRequest.id)
        assert bidderRequest.imp[0].bidFloor == bidRequest.imp[0].bidFloor
    }

    def "PBS should validate fetch.max-age-sec from account config"() {
        given: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account with enabled fetch, fetch.url, maxAgeSec in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.accountId).tap {
            config.auction.priceFloors.fetch = fetchConfig(bidRequest.accountId, DEFAULT_MAX_AGE_SEC - 1)
        }
        accountDao.save(account)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        then: "Metric alerts.account_config.ACCOUNT.price-floors should be update"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()
        assert metrics[INVALID_CONFIG_METRIC(bidRequest.accountId) as String] == 1

        and: "PBS floors validation failure should not reject the entire auction"
        assert !response.seatbid.isEmpty()

        where:
        fetchConfig << [{ String id, int max -> new PriceFloorsFetch(url: BASIC_FETCH_URL + id, enabled: true, maxAgeSec: max) },
                        { String id, int max -> new PriceFloorsFetch(url: BASIC_FETCH_URL + id, enabled: true, maxAgeSecSnakeCase: max) }]
    }

    def "PBS should validate fetch.period-sec from account config"() {
        given: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account with enabled fetch, fetch.url, periodSec in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.accountId).tap {
            config.auction.priceFloors.fetch = fetchConfig(DEFAULT_PERIOD_SEC,
                    defaultAccountConfigSettings.auction.priceFloors.fetch.maxAgeSec)
        }
        accountDao.save(account)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        then: "Metric alerts.account_config.ACCOUNT.price-floors  should be update"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()
        assert metrics[INVALID_CONFIG_METRIC(bidRequest.accountId) as String] == 1

        and: "PBS floors validation failure should not reject the entire auction"
        assert !response.seatbid?.isEmpty()

        where:
        fetchConfig << [{ int minPeriodSec, int maxAgeSec -> new PriceFloorsFetch(periodSec: minPeriodSec - 1) },
                        { int minPeriodSec, int maxAgeSec -> new PriceFloorsFetch(periodSec: maxAgeSec + 1) },
                        { int minPeriodSec, int maxAgeSec -> new PriceFloorsFetch(periodSecSnakeCase: minPeriodSec - 1) },
                        { int minPeriodSec, int maxAgeSec -> new PriceFloorsFetch(periodSecSnakeCase: maxAgeSec + 1) }]
    }

    def "PBS should validate fetch.max-file-size-kb from account config"() {
        given: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account with enabled fetch, fetch.url, maxFileSizeKb in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.accountId).tap {
            config.auction.priceFloors.fetch.maxFileSizeKb = PBSUtils.randomNegativeNumber
        }
        accountDao.save(account)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        then: "Metric alerts.account_config.ACCOUNT.price-floors should be update"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()
        assert metrics[INVALID_CONFIG_METRIC(bidRequest.accountId) as String] == 1

        and: "PBS floors validation failure should not reject the entire auction"
        assert !response.seatbid?.isEmpty()
    }

    def "PBS should validate fetch.max-rules from account config"() {
        given: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account with enabled fetch, fetch.url, maxRules in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.accountId).tap {
            config.auction.priceFloors.fetch.maxRules = PBSUtils.randomNegativeNumber
        }
        accountDao.save(account)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        then: "Metric alerts.account_config.ACCOUNT.price-floors  should be update"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()
        assert metrics[INVALID_CONFIG_METRIC(bidRequest.accountId) as String] == 1

        and: "PBS floors validation failure should not reject the entire auction"
        assert !response.seatbid?.isEmpty()
    }

    def "PBS should validate fetch.timeout-ms from account config"() {
        given: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account with enabled fetch, fetch.url, timeoutMs in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.accountId).tap {
            config.auction.priceFloors.fetch = fetchConfig(TIMEOUT_MS_MIN, TIMEOUT_MS_MAX)
        }
        accountDao.save(account)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        then: "Metric alerts.account_config.ACCOUNT.price-floors  should be update"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()
        assert metrics[INVALID_CONFIG_METRIC(bidRequest.accountId) as String] == 1

        and: "PBS floors validation failure should not reject the entire auction"
        assert !response.seatbid?.isEmpty()

        where:
        fetchConfig << [{ int min, int max -> new PriceFloorsFetch(timeoutMs: min - 1) },
                        { int min, int max -> new PriceFloorsFetch(timeoutMs: max + 1) },
                        { int min, int max -> new PriceFloorsFetch(timeoutMsSnakeCase: min - 1) },
                        { int min, int max -> new PriceFloorsFetch(timeoutMsSnakeCase: max + 1) }]
    }

    def "PBS should validate fetch.enforce-floors-rate from account config"() {
        given: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account with enabled fetch, fetch.url, enforceFloorsRate in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.accountId).tap {
            config.auction.priceFloors.tap {
                it.enforceFloorsRate = enforceFloorsRate
                it.enforceFloorsRateSnakeCase = enforceFloorsRateSnakeCase
            }
        }
        accountDao.save(account)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        then: "Metric alerts.account_config.ACCOUNT.price-floors  should be update"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()
        assert metrics[INVALID_CONFIG_METRIC(bidRequest.accountId) as String] == 1

        and: "PBS floors validation failure should not reject the entire auction"
        assert !response.seatbid?.isEmpty()

        where:
        enforceFloorsRate             | enforceFloorsRateSnakeCase
        PBSUtils.randomNegativeNumber | null
        ENFORCE_FLOORS_RATE_MAX + 1   | null
        null                          | PBSUtils.randomNegativeNumber
        null                          | ENFORCE_FLOORS_RATE_MAX + 1
    }

    def "PBS should fetch data from provider when price-floors.fetch.enabled = true in account config"() {
        given: "Default BidRequest with ext.prebid.floors"
        def bidRequest = BidRequest.getDefaultBidRequest(APP).tap {
            ext.prebid.floors = ExtPrebidFloors.extPrebidFloors
        }

        and: "Account with enabled fetch, fetch.url in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.accountId).tap {
            config.auction.priceFloors.fetch.enabled = true
        }
        accountDao.save(account)

        when: "PBS processes auction request"
        floorsPbsService.sendAuctionRequest(bidRequest)

        then: "PBS should fetch data from floors provider"
        assert floorsProvider.getRequestCount(bidRequest.accountId) == 1
    }

    def "PBS should process floors from request when price-floors.fetch.enabled = false in account config"() {
        given: "BidRequest with floors"
        def bidRequest = bidRequestWithFloors

        and: "Account with fetch.enabled, fetch.url in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.accountId).tap {
            config.auction.priceFloors.fetch = fetch
        }
        accountDao.save(account)

        and: "Set bidder response"
        def bidResponse = BidResponse.getDefaultBidResponse(bidRequest).tap {
            seatbid.first().bid.first().price = bidRequest.imp[0].bidFloor
        }
        bidder.setResponse(bidRequest.id, bidResponse)

        when: "PBS processes auction request"
        floorsPbsService.sendAuctionRequest(bidRequest)

        then: "PBS should not fetch data from floors provider"
        assert floorsProvider.getRequestCount(bidRequest.accountId) == 0

        and: "Bidder request should contain bidFloor from request"
        def bidderRequest = bidder.getBidderRequest(bidRequest.id)
        assert bidderRequest.imp[0].bidFloor == bidRequest.imp[0].bidFloor

        where:
        fetch << [new PriceFloorsFetch(enabled: false, url: BASIC_FETCH_URL), new PriceFloorsFetch(url: BASIC_FETCH_URL)]
    }

    def "PBS should fetch data from provider when use-dynamic-data enabled"() {
        given: "Pbs with PF configuration with useDynamicData"
        def defaultAccountConfigSettings = defaultAccountConfigSettings.tap {
            auction.priceFloors.tap {
                useDynamicData = pbsConfigUseDynamicData
                useDynamicDataSnakeCase = pbsConfigUseDynamicDataSnakeCase
            }
        }
        def pbsService = pbsServiceFactory.getService(FLOORS_CONFIG +
                ["settings.default-account-config": encode(defaultAccountConfigSettings)])

        and: "Default BidRequest with ext.prebid.floors"
        def bidRequest = BidRequest.getDefaultBidRequest(APP).tap {
            ext.prebid.floors = ExtPrebidFloors.extPrebidFloors
        }

        and: "Account with enabled fetch, fetch.url in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.accountId).tap {
            config.auction.priceFloors.useDynamicData = accountUseDynamicData
            config.auction.priceFloors.useDynamicDataSnakeCase = accountUseDynamicDataSnakeCase
        }
        accountDao.save(account)

        and: "Set Floors Provider response"
        def floorValue = PBSUtils.randomFloorValue
        def floorsResponse = PriceFloorData.priceFloorData.tap {
            modelGroups[0].values = [(rule): floorValue]
        }
        floorsProvider.setResponse(bidRequest.accountId, floorsResponse)

        when: "PBS cache rules and processes auction request"
        cacheFloorsProviderRules(bidRequest, floorValue, pbsService)

        then: "PBS should fetch data from floors provider"
        assert floorsProvider.getRequestCount(bidRequest.accountId) == 1

        and: "Bidder request should contain bidFloor from request"
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last()
        assert bidderRequest.imp[0].bidFloor == floorValue

        where:
        pbsConfigUseDynamicData | accountUseDynamicData | pbsConfigUseDynamicDataSnakeCase | accountUseDynamicDataSnakeCase
        false                   | true                  | null                             | null
        true                    | true                  | null                             | null
        true                    | null                  | null                             | null
        null                    | true                  | null                             | null
        null                    | null                  | false                            | true
        null                    | null                  | true                             | true
        null                    | null                  | true                             | null
        null                    | null                  | null                             | true
    }

    def "PBS should fetch data from provider when use-dynamic-data enabled and useFetchDataRate at max value"() {
        given: "Default BidRequest"
        def bidRequest = BidRequest.defaultBidRequest

        and: "Account with enabled use-dynamic-data parameter"
        def account = getAccountWithEnabledFetch(bidRequest.accountId).tap {
            config.auction.priceFloors.useDynamicData = true
        }
        accountDao.save(account)

        and: "Set Floors Provider response"
        def floorValue = PBSUtils.randomFloorValue
        def floorsResponse = PriceFloorData.priceFloorData.tap {
            modelGroups[0].values = [(rule): floorValue]
            useFetchDataRate = USE_FETCH_DATA_RATE_MAX
        }
        floorsProvider.setResponse(bidRequest.accountId, floorsResponse)

        and: "PBS fetch rules from floors provider"
        cacheFloorsProviderRules(bidRequest, floorsPbsService)

        when: "PBS processes auction request"
        floorsPbsService.sendAuctionRequest(bidRequest)

        then: "PBS should fetch data"
        assert floorsProvider.getRequestCount(bidRequest.accountId) == 1

        and: "Bidder request should contain floors data from floors provider"
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last
        verifyAll(bidderRequest) {
            imp[0].bidFloor == floorValue
            imp[0].bidFloorCur == floorsResponse.modelGroups[0].currency

            ext?.prebid?.floors?.location == FETCH
            ext?.prebid?.floors?.fetchStatus == SUCCESS
            ext?.prebid?.floors?.floorProvider == floorsResponse.floorProvider

            ext?.prebid?.floors?.skipRate == floorsResponse.skipRate
            ext?.prebid?.floors?.data == floorsResponse
        }

        and: "Bidder request shouldn't include imp.ext.prebid.floors"
        assert !bidderRequest.imp[0].ext.prebid.floors
    }

    def "PBS shouldn't fetch data from provider when use-dynamic-data disabled and useFetchDataRate at max value"() {
        given: "Default BidRequest"
        def bidRequest = BidRequest.defaultBidRequest

        and: "Account with disabled use-dynamic-data parameter"
        def account = getAccountWithEnabledFetch(bidRequest.accountId).tap {
            config.auction.priceFloors.useDynamicData = false
        }
        accountDao.save(account)

        and: "Set Floors Provider response"
        def floorValue = PBSUtils.randomFloorValue
        def floorsResponse = PriceFloorData.priceFloorData.tap {
            modelGroups[0].values = [(rule): floorValue]
            useFetchDataRate = USE_FETCH_DATA_RATE_MAX
        }
        floorsProvider.setResponse(bidRequest.accountId, floorsResponse)

        and: "PBS fetch rules from floors provider"
        cacheFloorsProviderRules(bidRequest, floorsPbsService)

        when: "PBS processes auction request"
        floorsPbsService.sendAuctionRequest(bidRequest)

        then: "PBS should fetch data"
        assert floorsProvider.getRequestCount(bidRequest.accountId) == 1

        and: "Bidder request shouldn't contain floors data from floors provider"
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last
        verifyAll(bidderRequest) {
            !imp[0].bidFloor
            !imp[0].bidFloorCur

            !imp[0].ext?.prebid?.floors?.floorRule
            !imp[0].ext?.prebid?.floors?.floorRuleValue
            !imp[0].ext?.prebid?.floors?.floorValue

            !ext?.prebid?.floors?.skipRate
            !ext?.prebid?.floors?.data
            !ext?.prebid?.floors?.floorProvider
            ext?.prebid?.floors?.location == NO_DATA
            ext?.prebid?.floors?.fetchStatus == SUCCESS
        }
    }

    def "PBS shouldn't fetch data from provider when use-dynamic-data enabled and useFetchDataRate at min value"() {
        given: "Default BidRequest"
        def bidRequest = BidRequest.defaultBidRequest

        and: "Account with enabled use-dynamic-data parameter"
        def account = getAccountWithEnabledFetch(bidRequest.accountId).tap {
            config.auction.priceFloors.useDynamicData = true
        }
        accountDao.save(account)

        and: "Set Floors Provider response"
        def floorValue = PBSUtils.randomFloorValue
        def floorsResponse = PriceFloorData.priceFloorData.tap {
            modelGroups[0].values = [(rule): floorValue]
            useFetchDataRate = USE_FETCH_DATA_RATE_MIN
        }
        floorsProvider.setResponse(bidRequest.accountId, floorsResponse)

        and: "PBS fetch rules from floors provider"
        cacheFloorsProviderRules(bidRequest, floorsPbsService)

        when: "PBS processes auction request"
        floorsPbsService.sendAuctionRequest(bidRequest)

        then: "PBS should fetch data"
        assert floorsProvider.getRequestCount(bidRequest.accountId) == 1

        and: "Bidder request shouldn't contain floors data from floors provider"
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last
        verifyAll(bidderRequest) {
            !imp[0].bidFloor
            !imp[0].bidFloorCur

            !imp[0].ext?.prebid?.floors?.floorRule
            !imp[0].ext?.prebid?.floors?.floorRuleValue
            !imp[0].ext?.prebid?.floors?.floorValue

            !ext?.prebid?.floors?.skipRate
            !ext?.prebid?.floors?.data
            !ext?.prebid?.floors?.floorProvider
            ext?.prebid?.floors?.location == NO_DATA
            ext?.prebid?.floors?.fetchStatus == SUCCESS
        }
    }

    def "PBS should log error and increase metrics when useFetchDataRate have invalid value"() {
        given: "Test start time"
        def startTime = Instant.now()

        and: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest()

        and: "Account with enabled fetch and fetch.url in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.accountId).tap {
            config.auction.priceFloors.useDynamicData = true
        }
        accountDao.save(account)

        and: "Flush metrics"
        flushMetrics(floorsPbsService)

        and: "Set Floors Provider response"
        def floorValue = PBSUtils.randomFloorValue
        def floorsResponse = PriceFloorData.priceFloorData.tap {
            modelGroups[0].values = [(rule): floorValue]
            useFetchDataRate = accounntUseFetchDataRate
        }
        floorsProvider.setResponse(bidRequest.accountId, floorsResponse)

        and: "PBS fetch rules from floors provider"
        cacheFloorsProviderRules(bidRequest, floorsPbsService, GENERIC, NONE)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        then: "metric should be updated"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()
        assert metrics[FETCH_FAILURE_METRIC] == 1

        and: "PBS should fetch data"
        assert floorsProvider.getRequestCount(bidRequest.accountId) == 1

        and: "PBS should not add warning or errors"
        assert !response.ext.warnings
        assert !response.ext.errors

        and: "PBS log should contain error"
        def logs = floorsPbsService.getLogsByTime(startTime)
        def message = "Price floor data useFetchDataRate must be in range(0-100), but was $accounntUseFetchDataRate"
        def floorsLogs = getLogsByText(logs, "$BASIC_FETCH_URL$bidRequest.accountId")
        assert floorsLogs.size() == 1
        assert floorsLogs[0].contains(FETCHING_FLOORS_ERROR_LOG(bidRequest, message))

        and: "Floors validation failure cannot reject the entire auction"
        assert !response.seatbid?.isEmpty()

        and: "Bidder request should contain floors data from floors provider"
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last
        verifyAll(bidderRequest) {
            !imp[0].bidFloor
            !imp[0].bidFloorCur

            !imp[0].ext?.prebid?.floors?.floorRule
            !imp[0].ext?.prebid?.floors?.floorRuleValue
            !imp[0].ext?.prebid?.floors?.floorValue

            !ext?.prebid?.floors?.floorProvider
            !ext?.prebid?.floors?.skipRate
            !ext?.prebid?.floors?.data
            ext?.prebid?.floors?.location == NO_DATA
            ext?.prebid?.floors?.fetchStatus == ERROR
        }

        where:
        accounntUseFetchDataRate << [PBSUtils.getRandomNegativeNumber(-USE_FETCH_DATA_RATE_MAX, USE_FETCH_DATA_RATE_MIN),
                                     PBSUtils.getRandomNumber(USE_FETCH_DATA_RATE_MAX + 1)
        ]
    }

    def "PBS should log merged error and increase metrics when useFetchDataRate have invalid value from provider and request"() {
        given: "BidRequest with invalid floors"
        def requestUseFetchDataRate = PBSUtils.getRandomNegativeNumber()
        def bidRequest = getBidRequestWithFloors().tap {
            it.ext.prebid.floors.data.useFetchDataRate = requestUseFetchDataRate
        }

        and: "Account with enabled fetch and fetch.url in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.accountId).tap {
            config.auction.priceFloors.useDynamicData = true
        }
        accountDao.save(account)

        and: "Flush metrics"
        flushMetrics(floorsPbsService)

        and: "Set Floors Provider response"
        def providerUseFetchDataRate = PBSUtils.getRandomNegativeNumber()
        def floorValue = PBSUtils.randomFloorValue
        def floorsResponse = PriceFloorData.priceFloorData.tap {
            modelGroups[0].values = [(rule): floorValue]
            useFetchDataRate = providerUseFetchDataRate
        }
        floorsProvider.setResponse(bidRequest.accountId, floorsResponse)

        and: "Default bid response"
        def bidResponse = BidResponse.getDefaultBidResponse(bidRequest)
        bidder.setResponse(bidRequest.id, bidResponse)

        and: "PBS fetch rules from floors provider"
        cacheFloorsProviderRules(bidRequest, floorsPbsService, GENERIC, ERROR)

        and: "Test start time"
        def startTime = Instant.now()

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        then: "metric should be updated"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()
        assert metrics[FETCH_FAILURE_METRIC] == 1

        and: "PBS should add single warning"
        assert response.ext?.warnings[PREBID]*.code == [999]
        assert response.ext?.warnings[PREBID]*.message ==
                [WARNING_MESSAGE("Price floor data useFetchDataRate must be in range(0-100), but was $requestUseFetchDataRate")]

        and: "PBS should not add error to request"
        assert !response.ext.errors

        and: "PBS should fetch data"
        assert floorsProvider.getRequestCount(bidRequest.accountId) == 1

        and: "PBS log should contain combined error log"
        def logs = floorsPbsService.getLogsByTime(startTime)
        def fetchingErrorLogs = getLogsByText(logs, "Price Floors can't be resolved for account $bidRequest.accountId " +
                "and request $bidRequest.id, reason: Price floors processing failed: Failed to fetch price floor from provider " +
                "for fetch.url '$BASIC_FETCH_URL${bidRequest.accountId}', with a reason: " +
                "Price floor data useFetchDataRate must be in range(0-100), but was $providerUseFetchDataRate. " +
                "Following parsing of request price floors is failed: " +
                "Price floor data useFetchDataRate must be in range(0-100), but was $requestUseFetchDataRate")
        assert fetchingErrorLogs.size() == 1

        and: "Floors validation failure cannot reject the entire auction"
        assert !response.seatbid?.isEmpty()

        and: "Bidder request should contain floors data from floors provider"
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last
        verifyAll(bidderRequest) {
            imp.bidFloor == bidRequest.imp.bidFloor
            imp.bidFloorCur == bidRequest.imp.bidFloorCur

            !imp[0].ext?.prebid?.floors?.floorRule
            !imp[0].ext?.prebid?.floors?.floorRuleValue
            !imp[0].ext?.prebid?.floors?.floorValue

            !ext?.prebid?.floors?.floorProvider
            !ext?.prebid?.floors?.skipRate
            !ext?.prebid?.floors?.data
            ext?.prebid?.floors?.location == NO_DATA
            ext?.prebid?.floors?.fetchStatus == ERROR
        }
    }

    def "PBS should process floors from request when use-dynamic-data = false"() {
        given: "Pbs with PF configuration with useDynamicData"
        def defaultAccountConfigSettings = defaultAccountConfigSettings.tap {
            auction.priceFloors.useDynamicData = pbsConfigUseDynamicData
        }
        def pbsService = pbsServiceFactory.getService(FLOORS_CONFIG +
                ["settings.default-account-config": encode(defaultAccountConfigSettings)])

        and: "BidRequest with floors"
        def bidRequest = bidRequestWithFloors

        and: "Account with fetch.enabled, fetch.url, useDynamicData in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.accountId).tap {
            config.auction.priceFloors.useDynamicData = accountUseDynamicData
        }
        accountDao.save(account)

        and: "PBS fetch rules from floors provider"
        cacheFloorsProviderRules(bidRequest, pbsService)

        when: "PBS processes auction request"
        pbsService.sendAuctionRequest(bidRequest)

        then: "PBS should fetch data from floors provider"
        assert floorsProvider.getRequestCount(bidRequest.accountId) == 1

        and: "Bidder request should contain bidFloor from request"
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last()
        assert bidderRequest.imp[0].bidFloor == bidRequest.imp[0].bidFloor

        where:
        pbsConfigUseDynamicData | accountUseDynamicData
        true                    | false
        false                   | false
        false                   | null
    }

    def "PBS should log error and increase #FETCH_FAILURE_METRIC metric when Floors Provider return status code != 200"() {
        given: "Test start time"
        def startTime = Instant.now()

        and: "Flush metrics"
        flushMetrics(floorsPbsService)

        and: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account with enabled fetch, fetch.url in the DB"
        def accountId = bidRequest.accountId
        def account = getAccountWithEnabledFetch(accountId)
        accountDao.save(account)

        and: "Set Floors Provider response  with status code != 200"
        floorsProvider.setResponse(accountId, BAD_REQUEST_400)

        and: "PBS fetch rules from floors provider"
        cacheFloorsProviderRules(bidRequest, floorsPbsService, GENERIC, NONE)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        and: "PBS processes collected metrics request"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()

        then: "#FETCH_FAILURE_METRIC should be update"
        assert metrics[FETCH_FAILURE_METRIC] == 1

        and: "PBS log should contain error"
        def message = "Failed to request, provider respond with status 400"
        def logs = floorsPbsService.getLogsByTime(startTime)
        def floorsLogs = getLogsByText(logs, "$BASIC_FETCH_URL$bidRequest.accountId")
        assert floorsLogs.size() == 1
        assert floorsLogs[0].contains(FETCHING_FLOORS_ERROR_LOG(bidRequest, message))

        and: "Floors validation failure cannot reject the entire auction"
        assert !response.seatbid?.isEmpty()
    }

    def "PBS should log error and increase #FETCH_FAILURE_METRIC metric when Floors Provider return invalid json"() {
        given: "Test start time"
        def startTime = Instant.now()

        and: "Flush metrics"
        flushMetrics(floorsPbsService)

        and: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account with enabled fetch, fetch.url in the DB"
        def accountId = bidRequest.accountId
        def account = getAccountWithEnabledFetch(accountId)
        accountDao.save(account)

        and: "Set Floors Provider response with invalid json"
        def invalidJson = "{{}}"
        floorsProvider.setResponse(accountId, invalidJson)

        and: "PBS fetch rules from floors provider"
        cacheFloorsProviderRules(bidRequest, floorsPbsService, GENERIC, NONE)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        and: "PBS processes collected metrics request"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()

        then: "#FETCH_FAILURE_METRIC should be update"
        assert metrics[FETCH_FAILURE_METRIC] == 1

        and: "PBS log should contain error"
        def message = "Failed to parse price floor response, cause: DecodeException: Failed to decode"
        def logs = floorsPbsService.getLogsByTime(startTime)
        def floorsLogs = getLogsByText(logs, "$BASIC_FETCH_URL$bidRequest.accountId")
        assert floorsLogs.size() == 1
        assert floorsLogs[0].contains(FETCHING_FLOORS_ERROR_LOG(bidRequest, message))

        and: "Floors validation failure cannot reject the entire auction"
        assert !response.seatbid?.isEmpty()
    }

    def "PBS should log error and increase #FETCH_FAILURE_METRIC when Floors Provider return empty response body"() {
        given: "Test start time"
        def startTime = Instant.now()

        and: "Flush metrics"
        flushMetrics(floorsPbsService)

        and: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account with enabled fetch, fetch.url in the DB"
        def accountId = bidRequest.accountId
        def account = getAccountWithEnabledFetch(accountId)
        accountDao.save(account)

        and: "Set Floors Provider response with invalid json"
        floorsProvider.setResponse(accountId, "")

        and: "PBS fetch rules from floors provider"
        cacheFloorsProviderRules(bidRequest, floorsPbsService, GENERIC, NONE)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        and: "PBS processes collected metrics request"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()

        then: "#FETCH_FAILURE_METRIC should be update"
        assert metrics[FETCH_FAILURE_METRIC] == 1

        and: "PBS log should contain error"
        def message = "Failed to parse price floor response, response body can not be empty"
        def logs = floorsPbsService.getLogsByTime(startTime)
        def floorsLogs = getLogsByText(logs, "$BASIC_FETCH_URL$bidRequest.accountId")
        assert floorsLogs.size() == 1
        assert floorsLogs[0].contains(FETCHING_FLOORS_ERROR_LOG(bidRequest, message))

        and: "Floors validation failure cannot reject the entire auction"
        assert !response.seatbid?.isEmpty()
    }

    def "PBS should log error and increase #FETCH_FAILURE_METRIC when Floors Provider response doesn't contain model"() {
        given: "Test start time"
        def startTime = Instant.now()

        and: "Flush metrics"
        flushMetrics(floorsPbsService)

        and: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account in the DB"
        def accountId = bidRequest.accountId
        def account = getAccountWithEnabledFetch(accountId)
        accountDao.save(account)

        and: "Set Floors Provider response without modelGroups"
        def floorsResponse = PriceFloorData.priceFloorData.tap {
            modelGroups = null
        }
        floorsProvider.setResponse(accountId, floorsResponse)

        and: "PBS fetch rules from floors provider"
        cacheFloorsProviderRules(bidRequest, floorsPbsService, GENERIC, NONE)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        and: "PBS processes collected metrics request"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()

        then: "#FETCH_FAILURE_METRIC should be update"
        assert metrics[FETCH_FAILURE_METRIC] == 1

        and: "PBS log should contain error"
        def message = "Price floor rules should contain at least one model group"
        def logs = floorsPbsService.getLogsByTime(startTime)
        def floorsLogs = getLogsByText(logs, "$BASIC_FETCH_URL$bidRequest.accountId")
        assert floorsLogs.size() == 1
        assert floorsLogs[0].contains(FETCHING_FLOORS_ERROR_LOG(bidRequest, message))

        and: "Floors validation failure cannot reject the entire auction"
        assert !response.seatbid?.isEmpty()
    }

    def "PBS should log error and increase #FETCH_FAILURE_METRIC when Floors Provider response doesn't contain rule"() {
        given: "Test start time"
        def startTime = Instant.now()

        and: "Flush metrics"
        flushMetrics(floorsPbsService)

        and: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account in the DB"
        def accountId = bidRequest.accountId
        def account = getAccountWithEnabledFetch(accountId)
        accountDao.save(account)

        and: "Set Floors Provider response without rules"
        def floorsResponse = PriceFloorData.priceFloorData.tap {
            modelGroups[0].values = null
        }
        floorsProvider.setResponse(accountId, floorsResponse)

        and: "PBS fetch rules from floors provider"
        cacheFloorsProviderRules(bidRequest, floorsPbsService, GENERIC, NONE)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        and: "PBS processes collected metrics request"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()

        then: "#FETCH_FAILURE_METRIC should be update"
        assert metrics[FETCH_FAILURE_METRIC] == 1

        and: "PBS log should contain error"
        def logs = floorsPbsService.getLogsByTime(startTime)
        def floorsLogs = getLogsByText(logs, "$BASIC_FETCH_URL$bidRequest.accountId")
        assert floorsLogs.size() == 1
        assert floorsLogs[0].contains(FETCHING_FLOORS_ERROR_LOG(bidRequest, PRICE_FLOOR_VALUES_MISSING))

        and: "Floors validation failure cannot reject the entire auction"
        assert !response.seatbid?.isEmpty()
    }

    def "PBS should log error and increase FETCH_FAILURE_METRIC when Floors Provider response has more than fetch.max-rules"() {
        given: "Test start time"
        def startTime = Instant.now()

        and: "Flush metrics"
        flushMetrics(floorsPbsService)

        and: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account in the DB"
        def accountId = bidRequest.accountId
        def maxRules = 1
        def account = getAccountWithEnabledFetch(accountId).tap {
            config.auction.priceFloors.fetch = fetchConfig(accountId, maxRules)
        }
        accountDao.save(account)

        and: "Set Floors Provider response with 2 rules"
        def floorsResponse = PriceFloorData.priceFloorData.tap {
            modelGroups[0].values.put(new Rule(mediaType: BANNER, country: MULTIPLE).rule, 0.7)
        }
        floorsProvider.setResponse(accountId, floorsResponse)

        and: "PBS fetch rules from floors provider"
        cacheFloorsProviderRules(bidRequest, floorsPbsService, GENERIC, NONE)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        and: "PBS processes collected metrics request"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()

        then: "#FETCH_FAILURE_METRIC should be update"
        assert metrics[FETCH_FAILURE_METRIC] == 1

        and: "PBS log should contain error"
        def message = "Price floor rules number 2 exceeded its maximum number $maxRules"
        def logs = floorsPbsService.getLogsByTime(startTime)
        def floorsLogs = getLogsByText(logs, "$BASIC_FETCH_URL$bidRequest.accountId")
        assert floorsLogs.size() == 1
        assert floorsLogs[0].contains(FETCHING_FLOORS_ERROR_LOG(bidRequest, message))

        and: "Floors validation failure cannot reject the entire auction"
        assert !response.seatbid?.isEmpty()

        where:
        fetchConfig << [{ String id, int max -> new PriceFloorsFetch(url: BASIC_FETCH_URL + id, enabled: true, maxRules: max) },
                        { String id, int max -> new PriceFloorsFetch(url: BASIC_FETCH_URL + id, enabled: true, maxRulesSnakeCase: max) }]
    }

    def "PBS should log error and increase #FETCH_FAILURE_METRIC when fetch request exceeds fetch.timeout-ms"() {
        given: "PBS with minTimeoutMs configuration"
        def pbsService = pbsServiceFactory.getService(FLOORS_CONFIG + ["price-floors.minTimeoutMs": "1"])

        and: "Test start time"
        def startTime = Instant.now()

        and: "Flush metrics"
        flushMetrics(pbsService)

        and: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account in the DB"
        def accountId = bidRequest.accountId
        def account = getAccountWithEnabledFetch(accountId)
        accountDao.save(account)

        and: "Set Floors Provider response with timeout"
        floorsProvider.setResponseWithTimeout(accountId)

        and: "PBS fetch rules from floors provider"
        cacheFloorsProviderRules(bidRequest, pbsService, GENERIC, NONE)

        when: "PBS processes auction request"
        def response = pbsService.sendAuctionRequest(bidRequest)

        and: "PBS processes collected metrics request"
        def metrics = pbsService.sendCollectedMetricsRequest()

        then: "#FETCH_FAILURE_METRIC should be update"
        assert metrics[FETCH_FAILURE_METRIC] == 1

        and: "PBS log should contain error"
        def logs = pbsService.getLogsByTime(startTime)
        def floorsLogs = getLogsByText(logs, "Price floor fetching failed for account $bidRequest.accountId: " +
                "Fetch price floor request timeout for fetch.url '$BASIC_FETCH_URL$accountId' exceeded")
        assert floorsLogs.size() == 1

        and: "Floors validation failure cannot reject the entire auction"
        assert !response.seatbid?.isEmpty()
    }

    def "PBS should log error and increase FETCH_FAILURE_METRIC when Floors Provider's response size is more than fetch.max-file-size-kb"() {
        given: "Test start time"
        def startTime = Instant.now()

        and: "Flush metrics"
        flushMetrics(floorsPbsService)

        and: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account with maxFileSizeKb in the DB"
        def accountId = bidRequest.accountId
        def maxSize = PBSUtils.getRandomNumber(1, 5)
        def account = getAccountWithEnabledFetch(accountId).tap {
            config.auction.priceFloors.fetch = fetchConfig(accountId, maxSize)
        }
        accountDao.save(account)

        and: "Set Floors Provider response with Content-Length"
        def floorsResponse = PriceFloorData.priceFloorData
        def responseSize = convertKilobyteSizeToByte(maxSize) + 75
        floorsProvider.setResponse(accountId, floorsResponse, ["Content-Length": responseSize as String])

        and: "PBS fetch rules from floors provider"
        cacheFloorsProviderRules(bidRequest, floorsPbsService, GENERIC, NONE)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        and: "PBS processes collected metrics request"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()

        then: "#FETCH_FAILURE_METRIC should be update"
        assert metrics[FETCH_FAILURE_METRIC] == 1

        and: "PBS log should contain error"
        def message = "Response size $responseSize exceeded ${convertKilobyteSizeToByte(maxSize)} bytes limit"
        def logs = floorsPbsService.getLogsByTime(startTime)
        def floorsLogs = getLogsByText(logs, "$BASIC_FETCH_URL$bidRequest.accountId")
        assert floorsLogs.size() == 1
        assert floorsLogs[0].contains(FETCHING_FLOORS_ERROR_LOG(bidRequest, message))

        and: "Floors validation failure cannot reject the entire auction"
        assert !response.seatbid?.isEmpty()

        where:
        fetchConfig << [{ String id, int maxKbSize -> new PriceFloorsFetch(url: BASIC_FETCH_URL + id, enabled: true, maxFileSizeKb: maxKbSize) },
                        { String id, int maxKbSize -> new PriceFloorsFetch(url: BASIC_FETCH_URL + id, enabled: true, maxFileSizeKbSnakeCase: maxKbSize) }]
    }

    def "PBS should prefer data from stored request when request doesn't contain floors data"() {
        given: "Default BidRequest with storedRequest"
        def storedRequestId = PBSUtils.randomNumber as String
        def bidRequest = request.tap {
            ext.prebid.storedRequest = new PrebidStoredRequest(id: storedRequestId)
        }

        and: "Default stored request with floors"
        def storedRequestModel = bidRequestWithFloors

        and: "Save storedRequest into DB"
        def storedRequest = StoredRequest.getStoredRequest(bidRequest.accountId, storedRequestId, storedRequestModel)
        storedRequestDao.save(storedRequest)

        and: "Account with disabled fetch in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.accountId).tap {
            config.auction.priceFloors.fetch.enabled = false
        }
        accountDao.save(account)

        when: "PBS processes auction request"
        floorsPbsService.sendAuctionRequest(bidRequest)

        then: "Bidder request should contain floors data from stored request"
        def bidderRequest = bidder.getBidderRequest(bidRequest.id)
        verifyAll(bidderRequest) {
            imp[0].bidFloor == storedRequestModel.ext.prebid.floors.data.modelGroups[0].values[rule]
            imp[0].bidFloorCur == storedRequestModel.ext.prebid.floors.data.modelGroups[0].currency

            ext?.prebid?.floors?.location == REQUEST
            ext?.prebid?.floors?.fetchStatus == NONE
            ext?.prebid?.floors?.floorMin == storedRequestModel.ext.prebid.floors.floorMin
            ext?.prebid?.floors?.floorProvider == storedRequestModel.ext.prebid.floors.data.floorProvider
            ext?.prebid?.floors?.data == storedRequestModel.ext.prebid.floors.data
        }

        and: "Bidder request shouldn't include imp.ext.prebid.floors"
        assert !bidderRequest.imp[0].ext.prebid.floors

        where:
        request                              | bidRequestWithFloors
        BidRequest.defaultBidRequest         | getBidRequestWithFloors(SITE)
        BidRequest.getDefaultBidRequest(APP) | getBidRequestWithFloors(APP)
    }

    def "PBS should prefer data from request when fetch is disabled in account config"() {
        given: "Default BidRequest"
        def bidRequest = bidRequestWithFloors

        and: "Account with disabled fetch in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.accountId).tap {
            config.auction.priceFloors.fetch.enabled = false
        }
        accountDao.save(account)

        and: "Set bidder response"
        def bidResponse = BidResponse.getDefaultBidResponse(bidRequest).tap {
            seatbid.first().bid.first().price = bidRequest.imp[0].bidFloor
        }
        bidder.setResponse(bidRequest.id, bidResponse)

        when: "PBS processes auction request"
        floorsPbsService.sendAuctionRequest(bidRequest)

        then: "Bidder request should contain floors data from request"
        def bidderRequest = bidder.getBidderRequest(bidRequest.id)
        verifyAll(bidderRequest) {
            imp[0].bidFloor == bidRequest.ext.prebid.floors.data.modelGroups[0].values[rule]
            imp[0].bidFloorCur == bidRequest.ext.prebid.floors.data.modelGroups[0].currency

            ext?.prebid?.floors?.location == REQUEST
            ext?.prebid?.floors?.fetchStatus == NONE
            ext?.prebid?.floors?.floorMin == bidRequest.ext.prebid.floors.floorMin
            ext?.prebid?.floors?.floorProvider == bidRequest.ext.prebid.floors.data.floorProvider
            ext?.prebid?.floors?.data == bidRequest.ext.prebid.floors.data
        }

        and: "Bidder request shouldn't include imp.ext.prebid.floors"
        assert !bidderRequest.imp[0].ext.prebid.floors
    }

    def "PBS should prefer data from stored request when fetch is disabled in account config for amp request"() {
        given: "Default AmpRequest"
        def ampRequest = AmpRequest.defaultAmpRequest

        and: "Default stored request with floors"
        def ampStoredRequest = storedRequestWithFloors
        def storedRequest = StoredRequest.getStoredRequest(ampRequest, ampStoredRequest)
        storedRequestDao.save(storedRequest)

        and: "Account with disabled fetch in the DB"
        def account = getAccountWithEnabledFetch(ampRequest.account as String).tap {
            config.auction.priceFloors.fetch.enabled = false
        }
        accountDao.save(account)

        when: "PBS processes amp request"
        floorsPbsService.sendAmpRequest(ampRequest)

        then: "Bidder request should contain floors data from request"
        def bidderRequest = bidder.getBidderRequest(ampStoredRequest.id)
        verifyAll(bidderRequest) {
            imp[0].bidFloor == ampStoredRequest.ext.prebid.floors.data.modelGroups[0].values[rule]
            imp[0].bidFloorCur == ampStoredRequest.ext.prebid.floors.data.modelGroups[0].currency

            ext?.prebid?.floors?.location == REQUEST
            ext?.prebid?.floors?.fetchStatus == NONE
            ext?.prebid?.floors?.floorMin == ampStoredRequest.ext.prebid.floors.floorMin
            ext?.prebid?.floors?.floorProvider == ampStoredRequest.ext.prebid.floors.data.floorProvider
            ext?.prebid?.floors?.data == ampStoredRequest.ext.prebid.floors.data
        }

        and: "Bidder request shouldn't include imp.ext.prebid.floors"
        assert !bidderRequest.imp[0].ext.prebid.floors
    }

    def "PBS should prefer data from floors provider when floors data is defined in both request and stored request"() {
        given: "BidRequest with storedRequest"
        def storedRequestId = PBSUtils.randomNumber as String
        def bidRequest = bidRequestWithFloors.tap {
            ext.prebid.storedRequest = new PrebidStoredRequest(id: storedRequestId)
            ext.prebid.floors.floorMin = FLOOR_MIN
        }

        and: "Default stored request with floors"
        def storedRequestModel = bidRequestWithFloors

        and: "Save storedRequest into DB"
        def storedRequest = StoredRequest.getStoredRequest(bidRequest.accountId, storedRequestId, storedRequestModel)
        storedRequestDao.save(storedRequest)

        and: "Account with enabled fetch, fetch.url in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.accountId)
        accountDao.save(account)

        and: "Set Floors Provider response"
        def floorValue = PBSUtils.randomFloorValue
        def floorsResponse = PriceFloorData.priceFloorData.tap {
            modelGroups[0].values = [(rule): floorValue]
        }
        floorsProvider.setResponse(bidRequest.accountId, floorsResponse)

        when: "PBS cache rules and processes auction request"
        cacheFloorsProviderRules(bidRequest, floorValue, floorsPbsService)

        then: "Bidder request should contain floors data from floors provider"
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last
        verifyAll(bidderRequest) {
            imp[0].bidFloor == floorValue
            imp[0].bidFloorCur == floorsResponse.modelGroups[0].currency

            ext?.prebid?.floors?.location == FETCH
            ext?.prebid?.floors?.fetchStatus == SUCCESS
            ext?.prebid?.floors?.floorMin == bidRequest.ext.prebid.floors.floorMin
            ext?.prebid?.floors?.floorProvider == floorsResponse.floorProvider

            ext?.prebid?.floors?.skipRate == floorsResponse.skipRate
            ext?.prebid?.floors?.data == floorsResponse
        }

        and: "Bidder request shouldn't include imp.ext.prebid.floors"
        assert !bidderRequest.imp[0].ext.prebid.floors
    }

    def "PBS should prefer data from floors provider when floors data is defined in stored request for amp request"() {
        given: "Default AmpRequest"
        def ampRequest = AmpRequest.defaultAmpRequest

        and: "Default stored request with floors "
        def ampStoredRequest = storedRequestWithFloors.tap {
            ext.prebid.floors.floorMin = FLOOR_MIN
        }
        def storedRequest = StoredRequest.getStoredRequest(ampRequest, ampStoredRequest)
        storedRequestDao.save(storedRequest)

        and: "Account with enabled fetch, fetch.url in the DB"
        def account = getAccountWithEnabledFetch(ampRequest.account as String)
        accountDao.save(account)

        and: "Set Floors Provider response"
        def floorValue = PBSUtils.randomFloorValue
        def floorsResponse = PriceFloorData.priceFloorData.tap {
            modelGroups[0].values = [(rule): floorValue]
        }
        floorsProvider.setResponse(ampRequest.account as String, floorsResponse)

        when: "PBS cache rules and processes amp request"
        cacheFloorsProviderRules(ampRequest, floorValue)

        then: "Bidder request should contain floors data from floors provider"
        def bidderRequest = bidder.getBidderRequests(ampStoredRequest.id).last()
        verifyAll(bidderRequest) {
            imp[0].bidFloor == floorValue
            imp[0].bidFloorCur == floorsResponse.modelGroups[0].currency

            ext?.prebid?.floors?.location == FETCH
            ext?.prebid?.floors?.fetchStatus == SUCCESS
            ext?.prebid?.floors?.floorMin == ampStoredRequest.ext.prebid.floors.floorMin
            ext?.prebid?.floors?.floorProvider == floorsResponse.floorProvider

            ext?.prebid?.floors?.skipRate == floorsResponse.skipRate
            ext?.prebid?.floors?.data == floorsResponse
        }

        and: "Bidder request shouldn't include imp.ext.prebid.floors"
        assert !bidderRequest.imp[0].ext.prebid.floors
    }

    def "PBS should periodically fetch floor rules when previous response from floors provider is #description"() {
        given: "PBS with PF configuration with minMaxAgeSec"
        def pbsService = pbsServiceFactory.getService(FLOORS_CONFIG + ["price-floors.minMaxAgeSec": "3",
                                                                       "price-floors.minPeriodSec": "3"])

        and: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account with enabled fetch, fetch.url in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.accountId)
        accountDao.save(account)

        and: "Set Floors Provider #description response"
        floorsProvider.setResponse(bidRequest.accountId, floorsResponse)

        when: "PBS processes auction request"
        pbsService.sendAuctionRequest(bidRequest)

        then: "PBS should cache data from data provider"
        assert floorsProvider.getRequestCount(bidRequest.accountId) == 1

        and: "PBS should periodically fetch data from data provider"
        PBSUtils.waitUntil({ floorsProvider.getRequestCount(bidRequest.accountId) > 1 }, 7000, 3000)

        where:
        description | floorsResponse
        "valid"     | PriceFloorData.priceFloorData
        "invalid"   | PriceFloorData.priceFloorData.tap { modelGroups = null }
    }

    def "PBS should continue to hold onto previously fetched rules when fetch.enabled = false in account config"() {
        given: "PBS with PF configuration"
        def defaultAccountConfigSettings = defaultAccountConfigSettings.tap {
            auction.priceFloors.fetch.maxAgeSec = 86400
        }
        def pbsService = pbsServiceFactory.getService(FLOORS_CONFIG +
                ["settings.default-account-config": encode(defaultAccountConfigSettings)])

        and: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account with enabled fetch, fetch.url in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.accountId)
        accountDao.save(account)

        and: "Set Floors Provider #description response"
        def floorValue = PBSUtils.randomFloorValue
        def floorsResponse = PriceFloorData.priceFloorData.tap {
            modelGroups[0].values = [(rule): floorValue]
        }
        floorsProvider.setResponse(bidRequest.accountId, floorsResponse)

        when: "PBS processes auction request"
        pbsService.sendAuctionRequest(bidRequest)

        and: "Account with disabled fetch in the DB"
        account.tap {
            config.auction.priceFloors.fetch.enabled = false
        }
        accountDao.update(account)

        and: "PBS processes auction request"
        pbsService.sendAuctionRequest(bidRequest)

        then: "Bidder request should contain previously cached bidFloor"
        assert bidder.getRequestCount(bidRequest.id) == 2
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last()

        verifyAll(bidderRequest) {
            imp[0].bidFloor == floorValue
            imp[0].bidFloorCur == floorsResponse.modelGroups[0].currency

            ext?.prebid?.floors?.location == FETCH
            ext?.prebid?.floors?.fetchStatus == SUCCESS
            !ext?.prebid?.floors?.floorMin
            ext?.prebid?.floors?.floorProvider == floorsResponse.floorProvider

            ext?.prebid?.floors?.skipRate == floorsResponse.skipRate
            ext?.prebid?.floors?.data == floorsResponse
        }

        and: "Bidder request shouldn't include imp.ext.prebid.floors"
        assert !bidderRequest.imp[0].ext.prebid.floors
    }

    def "PBS should validate rules from request when floorMin from request is invalid"() {
        given: "Default BidRequest with floorMin"
        def floorValue = PBSUtils.randomFloorValue
        def invalidFloorMin = FLOOR_MIN - 1
        def bidRequest = bidRequestWithFloors.tap {
            imp[0].bidFloor = floorValue
            ext.prebid.floors.floorMin = invalidFloorMin
        }

        and: "Account with disabled fetch in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.accountId).tap {
            config.auction.priceFloors.fetch.enabled = false
        }
        accountDao.save(account)

        and: "Default bid response"
        def bidResponse = BidResponse.getDefaultBidResponse(bidRequest)
        bidder.setResponse(bidRequest.id, bidResponse)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        then: "Bidder request bidFloor should correspond to request.imp.bidFloor"
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last()
        assert bidderRequest.imp[0].bidFloor == floorValue

        and: "Response should contain warning"
        def message = "Price floor floorMin must be positive float, but was $invalidFloorMin"
        assert response.ext?.warnings[PREBID]*.code == [999]
        assert response.ext?.warnings[PREBID]*.message == [WARNING_MESSAGE(message)]
    }

    def "PBS should validate rules from request when request doesn't contain modelGroups"() {
        given: "Default BidRequest without modelGroups"
        def floorValue = PBSUtils.randomFloorValue
        def bidRequest = bidRequestWithFloors.tap {
            imp[0].bidFloor = floorValue
            ext.prebid.floors.data.modelGroups = null
        }

        and: "Account with disabled fetch in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.accountId).tap {
            config.auction.priceFloors.fetch.enabled = false
        }
        accountDao.save(account)

        and: "Default bid response"
        def bidResponse = BidResponse.getDefaultBidResponse(bidRequest)
        bidder.setResponse(bidRequest.id, bidResponse)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        then: "Bidder request bidFloor should correspond to request.imp.bidFloor"
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last()
        assert bidderRequest.imp[0].bidFloor == floorValue

        and: "Response should contain warning"
        def message = "Price floor rules should contain at least one model group"
        assert response.ext?.warnings[PREBID]*.code == [999]
        assert response.ext?.warnings[PREBID]*.message == [WARNING_MESSAGE(message)]
    }

    def "PBS should validate rules from request when request doesn't contain values"() {
        given: "Default BidRequest without rules"
        def floorValue = PBSUtils.randomFloorValue
        def bidRequest = bidRequestWithFloors.tap {
            imp[0].bidFloor = floorValue
            ext.prebid.floors.data.modelGroups[0].values = null
        }

        and: "Account with disabled fetch in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.accountId).tap {
            config.auction.priceFloors.fetch.enabled = false
        }
        accountDao.save(account)

        and: "Default bid response"
        def bidResponse = BidResponse.getDefaultBidResponse(bidRequest)
        bidder.setResponse(bidRequest.id, bidResponse)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        then: "Bidder request bidFloor should correspond to request.imp.bidFloor"
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last()
        assert bidderRequest.imp[0].bidFloor == floorValue

        and: "Response should contain warning"
        assert response.ext?.warnings[PREBID]*.code == [999]
        assert response.ext?.warnings[PREBID]*.message == [WARNING_MESSAGE(PRICE_FLOOR_VALUES_MISSING)]
    }

    def "PBS should validate rules from request when modelWeight from request is invalid"() {
        given: "Default BidRequest with floors"
        def floorValue = PBSUtils.randomFloorValue
        def bidRequest = bidRequestWithFloors.tap {
            imp[0].bidFloor = floorValue
            ext.prebid.floors.data.modelGroups << FloorModelGroup.modelGroup
            ext.prebid.floors.data.modelGroups.first().values = [(rule): floorValue + 0.1]
            ext.prebid.floors.data.modelGroups.first().modelWeight = invalidModelWeight
            ext.prebid.floors.data.modelGroups.last().values = [(rule): floorValue + 0.2]
            ext.prebid.floors.data.modelGroups.last().modelWeight = modelWeight
        }

        and: "Account with disabled fetch in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.accountId).tap {
            config.auction.priceFloors.fetch.enabled = false
        }
        accountDao.save(account)

        and: "Default bid response"
        def bidResponse = BidResponse.getDefaultBidResponse(bidRequest)
        bidder.setResponse(bidRequest.id, bidResponse)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        then: "Bidder request bidFloor should correspond to request.imp.bidFloor"
        def bidderRequest = bidder.getBidderRequest(bidRequest.id)
        assert bidderRequest.imp[0].bidFloor == floorValue

        and: "Response should contain warning"
        assert response.ext?.warnings[PREBID]*.code == [999]
        assert response.ext?.warnings[PREBID]*.message == [WARNING_MESSAGE(MODEL_WEIGHT_INVALID.formatted(invalidModelWeight))]

        where:
        invalidModelWeight << [0, MAX_MODEL_WEIGHT + 1]
    }

    def "PBS should validate rules from amp request when modelWeight from request is invalid"() {
        given: "Default AmpRequest"
        def ampRequest = AmpRequest.defaultAmpRequest

        and: "Default stored request with floors"
        def floorValue = PBSUtils.randomFloorValue
        def ampStoredRequest = storedRequestWithFloors.tap {
            imp[0].bidFloor = floorValue
            ext.prebid.floors.data.modelGroups << FloorModelGroup.modelGroup
            ext.prebid.floors.data.modelGroups.first().values = [(rule): floorValue + 0.1]
            ext.prebid.floors.data.modelGroups.first().modelWeight = invalidModelWeight
            ext.prebid.floors.data.modelGroups.last().values = [(rule): floorValue + 0.2]
            ext.prebid.floors.data.modelGroups.last().modelWeight = modelWeight
        }
        def storedRequest = StoredRequest.getStoredRequest(ampRequest, ampStoredRequest)
        storedRequestDao.save(storedRequest)

        and: "Account with disabled fetch in the DB"
        def account = getAccountWithEnabledFetch(ampRequest.account as String).tap {
            config.auction.priceFloors.fetch.enabled = false
        }
        accountDao.save(account)

        and: "Default bid response"
        def bidResponse = BidResponse.getDefaultBidResponse(ampStoredRequest)
        bidder.setResponse(ampStoredRequest.id, bidResponse)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAmpRequest(ampRequest)

        then: "Bidder request bidFloor should correspond to valid modelGroup"
        def bidderRequest = bidder.getBidderRequests(ampStoredRequest.id).last()
        assert bidderRequest.imp[0].bidFloor == floorValue

        and: "Response should contain warning"
        assert response.ext?.warnings[PREBID]*.code == [999]
        assert response.ext?.warnings[PREBID]*.message == [WARNING_MESSAGE(MODEL_WEIGHT_INVALID.formatted(invalidModelWeight))]

        where:
        invalidModelWeight << [0, MAX_MODEL_WEIGHT + 1]
    }

    def "PBS should reject fetch when root skipRate from request is invalid"() {
        given: "Default BidRequest with skipRate"
        def floorValue = PBSUtils.randomFloorValue
        def bidRequest = bidRequestWithFloors.tap {
            imp[0].bidFloor = floorValue
            ext.prebid.floors.data.modelGroups << FloorModelGroup.modelGroup
            ext.prebid.floors.data.modelGroups.first().values = [(rule): floorValue + 0.1]
            ext.prebid.floors.data.modelGroups[0].skipRate = 0
            ext.prebid.floors.data.skipRate = 0
            ext.prebid.floors.skipRate = invalidSkipRate
            ext.prebid.floors.data.modelGroups.last().values = [(rule): floorValue + 0.2]
            ext.prebid.floors.data.modelGroups.last().skipRate = 0
        }

        and: "Account with disabled fetch in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.accountId).tap {
            config.auction.priceFloors.fetch.enabled = false
        }
        accountDao.save(account)

        and: "PBS fetch rules from floors provider"
        cacheFloorsProviderRules(bidRequest)

        and: "Default bid response"
        def bidResponse = BidResponse.getDefaultBidResponse(bidRequest)
        bidder.setResponse(bidRequest.id, bidResponse)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        then: "Bidder request bidFloor should correspond to request.imp.bidFloor"
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last()
        assert bidderRequest.imp[0].bidFloor == floorValue

        and: "Response should contain warning"
        def message = "Price floor root skipRate must be in range(0-100), but was $invalidSkipRate"
        assert response.ext?.warnings[PREBID]*.code == [999]
        assert response.ext?.warnings[PREBID]*.message == [WARNING_MESSAGE(message)]

        where:
        invalidSkipRate << [SKIP_RATE_MIN - 1, SKIP_RATE_MAX + 1]
    }

    def "PBS should reject fetch when data skipRate from request is invalid"() {
        given: "Default BidRequest with skipRate"
        def floorValue = PBSUtils.randomFloorValue
        def bidRequest = bidRequestWithFloors.tap {
            imp[0].bidFloor = floorValue
            ext.prebid.floors.data.modelGroups << FloorModelGroup.modelGroup
            ext.prebid.floors.data.modelGroups.first().values = [(rule): floorValue + 0.1]
            ext.prebid.floors.data.modelGroups[0].skipRate = 0
            ext.prebid.floors.data.skipRate = invalidSkipRate
            ext.prebid.floors.skipRate = 0
            ext.prebid.floors.data.modelGroups.last().values = [(rule): floorValue + 0.2]
            ext.prebid.floors.data.modelGroups.last().skipRate = 0
        }

        and: "Account with disabled fetch in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.accountId).tap {
            config.auction.priceFloors.fetch.enabled = false
        }
        accountDao.save(account)

        and: "PBS fetch rules from floors provider"
        cacheFloorsProviderRules(bidRequest)

        and: "Default bid response"
        def bidResponse = BidResponse.getDefaultBidResponse(bidRequest)
        bidder.setResponse(bidRequest.id, bidResponse)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        then: "Bidder request bidFloor should correspond to request.imp.bidFloor"
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last()
        assert bidderRequest.imp[0].bidFloor == floorValue

        and: "Response should contain warning"
        def message = "Price floor data skipRate must be in range(0-100), but was $invalidSkipRate"
        assert response.ext?.warnings[PREBID]*.code == [999]
        assert response.ext?.warnings[PREBID]*.message == [WARNING_MESSAGE(message)]

        where:
        invalidSkipRate << [SKIP_RATE_MIN - 1, SKIP_RATE_MAX + 1]
    }

    def "PBS should reject fetch when modelGroup skipRate from request is invalid"() {
        given: "Default BidRequest with skipRate"
        def floorValue = PBSUtils.randomFloorValue
        def bidRequest = bidRequestWithFloors.tap {
            imp[0].bidFloor = floorValue
            ext.prebid.floors.data.modelGroups << FloorModelGroup.modelGroup
            ext.prebid.floors.data.modelGroups.first().values = [(rule): floorValue + 0.1]
            ext.prebid.floors.data.modelGroups[0].skipRate = invalidSkipRate
            ext.prebid.floors.data.skipRate = 0
            ext.prebid.floors.skipRate = 0
            ext.prebid.floors.data.modelGroups.last().values = [(rule): floorValue + 0.2]
            ext.prebid.floors.data.modelGroups.last().skipRate = 0
        }

        and: "Account with disabled fetch in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.accountId).tap {
            config.auction.priceFloors.fetch.enabled = false
        }
        accountDao.save(account)

        and: "PBS fetch rules from floors provider"
        cacheFloorsProviderRules(bidRequest)

        and: "Default bid response"
        def bidResponse = BidResponse.getDefaultBidResponse(bidRequest)
        bidder.setResponse(bidRequest.id, bidResponse)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        then: "Bidder request bidFloor should correspond to request.imp.bidFloor"
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last()
        assert bidderRequest.imp[0].bidFloor == floorValue

        and: "Response should contain warning"
        assert response.ext?.warnings[PREBID]*.code == [999]
        assert response.ext?.warnings[PREBID]*.message == [WARNING_MESSAGE(SKIP_RATE_INVALID.formatted(invalidSkipRate))]

        where:
        invalidSkipRate << [SKIP_RATE_MIN - 1, SKIP_RATE_MAX + 1]
    }

    def "PBS should validate rules from request when default floor value from request is invalid"() {
        given: "Default BidRequest with default floor value"
        def floorValue = PBSUtils.randomFloorValue
        def invalidDefaultFloorValue = DEFAULT_FLOOR_VALUE_MIN - 1
        def bidRequest = bidRequestWithFloors.tap {
            imp[0].bidFloor = floorValue
            ext.prebid.floors.data.modelGroups << FloorModelGroup.modelGroup
            ext.prebid.floors.data.modelGroups.first().values = [(rule): floorValue + 0.1]
            ext.prebid.floors.data.modelGroups[0].defaultFloor = invalidDefaultFloorValue
            ext.prebid.floors.data.modelGroups.last().values = [(rule): floorValue + 0.2]
            ext.prebid.floors.data.modelGroups.last().defaultFloor = DEFAULT_FLOOR_VALUE_MIN
        }

        and: "Account with disabled fetch in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.accountId).tap {
            config.auction.priceFloors.fetch.enabled = false
        }
        accountDao.save(account)

        and: "Default bid response"
        def bidResponse = BidResponse.getDefaultBidResponse(bidRequest)
        bidder.setResponse(bidRequest.id, bidResponse)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        then: "Bidder request bidFloor should correspond to request.imp.bidFloor"
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last()
        assert bidderRequest.imp[0].bidFloor == floorValue

        and: "Response should contain warning"
        def message = "Price floor modelGroup default must be positive float, but was $invalidDefaultFloorValue"
        assert response.ext?.warnings[PREBID]*.code == [999]
        assert response.ext?.warnings[PREBID]*.message == [WARNING_MESSAGE(message)]
    }

    def "PBS shouldn't emit error in log and response when floors is not in request and floors fetching disabled for account"() {
        given: "Account with disabled fetching"
        def account = getAccountWithEnabledFetch(bidRequest.accountId).tap {
            config.auction.priceFloors.fetch.enabled = false
            config.auction.priceFloors.fetch.url = null
        }
        accountDao.save(account)

        and: "Default bid response"
        def response = BidResponse.getDefaultBidResponse(bidRequest)
        bidder.setResponse(bidRequest.id, response)

        and: "Flush metrics"
        flushMetrics(floorsPbsService)

        when: "PBS processes auction request"
        def bidResponse = floorsPbsService.sendAuctionRequest(bidRequest)

        then: "PBS shouldn't log warning or errors"
        assert !bidResponse.ext?.warnings
        assert !bidResponse.ext?.errors

        and: "PBS shouldn't log a errors"
        def message = "Price floor rules data must be present"
        def logs = floorsPbsService.getLogsByTime(startTime)
        def floorsLogs = getLogsByText(logs, PRICE_FLOORS_ERROR_LOG(bidRequest, FETCHING_DISABLED_ERROR, message))
        assert !floorsLogs.size()

        and: "PBS request on response object status should be in progress"
        assert getRequests(bidResponse)[GENERIC.value]?.first?.ext?.prebid?.floors?.fetchStatus == NONE

        and: "PBS bidderRequest status should be in progress"
        def bidderRequest = bidder.getBidderRequests(bidRequest.id)
        assert bidderRequest.ext.prebid.floors.fetchStatus == [NONE]

        and: "Alerts.general metrics shouldn't be populated"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()
        assert !metrics[ALERT_GENERAL]

        where:
        bidRequest << [BidRequest.getDefaultBidRequest(), getBidRequestWithFloors().tap { it.ext.prebid.floors = null }]
    }

    def "PBS should emit error in log and response when floor data is empty and floors fetching disabled for account and #requestFloorEnabled for request"() {
        given: "Default BidRequest with empty floors.data"
        def bidRequest = bidRequestWithFloors.tap {
            it.ext.prebid.floors.enabled = requestFloorEnabled
            it.ext.prebid.floors.data = null
        }

        and: "Account with disabled fetching"
        def account = getAccountWithEnabledFetch(bidRequest.accountId).tap {
            it.config.auction.priceFloors.fetch.enabled = false
        }
        accountDao.save(account)

        and: "Default bid response"
        def response = BidResponse.getDefaultBidResponse(bidRequest)
        bidder.setResponse(bidRequest.id, response)

        and: "Flush metrics"
        flushMetrics(floorsPbsService)

        when: "PBS processes auction request"
        def bidResponse = floorsPbsService.sendAuctionRequest(bidRequest)

        then: "PBS should log a warning"
        def message = "Price floor rules data must be present"
        assert bidResponse.ext?.warnings[PREBID]*.code == [999]
        assert bidResponse.ext?.warnings[PREBID]*.message == [WARNING_MESSAGE(message)]

        and: "PBS should not add errors"
        assert !bidResponse.ext.errors

        and: "PBS should log a errors"
        def logs = floorsPbsService.getLogsByTime(startTime)
        def floorsLogs = getLogsByText(logs, PRICE_FLOORS_ERROR_LOG(bidRequest, FETCHING_DISABLED_ERROR, message))
        assert floorsLogs.size() == 1

        and: "PBS request on response object status should be not in progress"
        assert getRequests(bidResponse)[GENERIC.value]?.first?.ext?.prebid?.floors?.fetchStatus == NONE

        and: "PBS request status shouldn't be in progress"
        def bidderRequest = bidder.getBidderRequests(bidRequest.id)
        assert bidderRequest.ext.prebid.floors.fetchStatus == [NONE]

        and: "Alerts.general metrics should be populated"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()
        assert metrics[ALERT_GENERAL] == 1

        where:
        requestFloorEnabled << [null, true]
    }

    def "PBS shouldn't emit error in log and response when floor data is empty and floors fetching disabled for account and floors disabled for request"() {
        given: "Default BidRequest with empty floors.data"
        def bidRequest = bidRequestWithFloors.tap {
            it.ext.prebid.floors.enabled = false
            it.ext.prebid.floors.data = null
        }

        and: "Account with disabled fetching"
        def account = getAccountWithEnabledFetch(bidRequest.accountId).tap {
            it.config.auction.priceFloors.fetch.enabled = false
        }
        accountDao.save(account)

        and: "Default bid response"
        def response = BidResponse.getDefaultBidResponse(bidRequest)
        bidder.setResponse(bidRequest.id, response)

        and: "Flush metrics"
        flushMetrics(floorsPbsService)

        when: "PBS processes auction request"
        def bidResponse = floorsPbsService.sendAuctionRequest(bidRequest)

        then: "PBS shouldn't log warning or errors"
        assert !bidResponse.ext?.warnings
        assert !bidResponse.ext?.errors

        and: "PBS shouldn't log a errors"
        def message = "Price floor rules data must be present"
        def logs = floorsPbsService.getLogsByTime(startTime)
        def floorsLogs = getLogsByText(logs, PRICE_FLOORS_ERROR_LOG(bidRequest, FETCHING_DISABLED_ERROR, message))
        assert !floorsLogs.size()

        and: "Alerts.general metrics shouldn't be populated"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()
        assert !metrics[ALERT_GENERAL]
    }

    def "PBS shouldn't emit error in log and response when data is invalid and floors fetching enabled for account"() {
        given: "Default BidRequest with empty floors.data"
        def bidRequest = bidRequestWithFloors.tap {
            ext.prebid.floors.enabled = requestEnabledFloors
            ext.prebid.floors.data = null
        }

        and: "Account with enabled fetching"
        def account = getAccountWithEnabledFetch(bidRequest.accountId)
        accountDao.save(account)

        and: "Default bid response"
        def response = BidResponse.getDefaultBidResponse(bidRequest)
        bidder.setResponse(bidRequest.id, response)

        and: "Flush metrics"
        flushMetrics(floorsPbsService)

        when: "PBS processes auction request"
        def bidResponse = floorsPbsService.sendAuctionRequest(bidRequest)

        then: "PBS shouldn't log warning or errors"
        assert !bidResponse.ext?.warnings
        assert !bidResponse.ext?.errors

        and: "PBS shouldn't log a errors"
        def message = "Price floor rules data must be present"
        def logs = floorsPbsService.getLogsByTime(startTime)
        def floorsLogs = getLogsByText(logs, PRICE_FLOORS_ERROR_LOG(bidRequest, FETCHING_DISABLED_ERROR, message))
        assert !floorsLogs.size()

        and: "PBS request on response object status should be in progress"
        assert getRequests(bidResponse)[GENERIC.value]?.first?.ext?.prebid?.floors?.fetchStatus == INPROGRESS

        and: "PBS bidderRequest status should be in progress"
        def bidderRequest = bidder.getBidderRequests(bidRequest.id)
        assert bidderRequest.ext.prebid.floors.fetchStatus == [INPROGRESS]

        and: "Alerts.general metrics shouldn't be populated"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()
        assert !metrics[ALERT_GENERAL]

        where:
        requestEnabledFloors << [null, true]
    }

    def "PBS shouldn't emit error in log and response when data is invalid and floors disabled for account"() {
        given: "Default BidRequest with empty floors.data"
        def bidRequest = bidRequestWithFloors.tap {
            ext.prebid.floors.data = null
        }

        and: "Account with disabled fetching"
        def account = getAccountWithEnabledFetch(bidRequest.accountId).tap {
            config.auction.priceFloors.enabled = false
            config.auction.priceFloors.fetch.enabled = false
        }
        accountDao.save(account)

        and: "PBS fetch rules from floors provider"
        cacheFloorsProviderRules(bidRequest, floorsPbsService)

        and: "Default bid response"
        def response = BidResponse.getDefaultBidResponse(bidRequest)
        bidder.setResponse(bidRequest.id, response)

        and: "Flush metrics"
        flushMetrics(floorsPbsService)

        when: "PBS processes auction request"
        def bidResponse = floorsPbsService.sendAuctionRequest(bidRequest)

        then: "PBS should not add warning or errors"
        assert !bidResponse.ext.warnings
        assert !bidResponse.ext.errors

        and: "PBS request on response object status should be empty"
        assert getRequests(bidResponse)[GENERIC.value]?.first?.ext?.prebid?.floors?.fetchStatus == null

        and: "PBS request status should be empty"
        def bidderRequest = bidder.getBidderRequests(bidRequest.id)
        assert bidderRequest.ext.prebid.floors.fetchStatus.every { it == null }

        and: "Alerts.general metrics shouldn't be populated"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()
        assert !metrics[ALERT_GENERAL]
    }

    def "PBS should not invalidate previously good fetched data when floors provider return invalid data"() {
        given: "PBS with PF configuration with minMaxAgeSec"
        def pbsService = pbsServiceFactory.getService(FLOORS_CONFIG + ["price-floors.minMaxAgeSec": "3",
                                                                       "price-floors.minPeriodSec": "3"])

        and: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account with enabled fetch, fetch.url in the DB"
        def accountId = bidRequest.accountId
        def account = getAccountWithEnabledFetch(accountId)
        accountDao.save(account)

        and: "Set Floors Provider #description response"
        def floorValue = PBSUtils.randomFloorValue
        def floorsResponse = PriceFloorData.priceFloorData.tap {
            modelGroups[0].values = [(rule): floorValue]
        }
        floorsProvider.setResponse(accountId, floorsResponse)

        and: "PBS cache rules"
        cacheFloorsProviderRules(bidRequest, floorValue, pbsService)

        and: "Set Floors Provider response  with status code != 200"
        floorsProvider.setResponse(accountId, BAD_REQUEST_400)

        when: "PBS processes auction request"
        pbsService.sendAuctionRequest(bidRequest)

        then: "Bidder request should contain previously cached floor data"
        assert bidder.getRequestCount(bidRequest.id) > 1
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last()

        verifyAll(bidderRequest) {
            imp[0].bidFloor == floorValue
            imp[0].bidFloorCur == floorsResponse.modelGroups[0].currency

            ext?.prebid?.floors?.location == FETCH
            ext?.prebid?.floors?.fetchStatus == SUCCESS
            !ext?.prebid?.floors?.floorMin
            ext?.prebid?.floors?.floorProvider == floorsResponse.floorProvider

            ext?.prebid?.floors?.skipRate == floorsResponse.skipRate
            ext?.prebid?.floors?.data == floorsResponse
        }

        and: "Bidder request shouldn't include imp.ext.prebid.floors"
        assert !bidderRequest.imp[0].ext.prebid.floors
    }

    def "PBS should reject fetch when modelWeight from floors provider is invalid"() {
        given: "Test start time"
        def startTime = Instant.now()

        and: "Flush metrics"
        flushMetrics(floorsPbsService)

        and: "Default BidRequest"
        def bidRequest = BidRequest.defaultBidRequest

        and: "Account with enabled fetch, fetch.url in the DB"
        def accountId = bidRequest.accountId
        def account = getAccountWithEnabledFetch(accountId)
        accountDao.save(account)

        and: "Set Floors Provider response"
        def floorValue = PBSUtils.randomFloorValue
        def floorsResponse = PriceFloorData.priceFloorData.tap {
            modelGroups << FloorModelGroup.modelGroup
            modelGroups.first().values = [(rule): floorValue + 0.1]
            modelGroups.first().modelWeight = invalidModelWeight
            modelGroups.last().values = [(rule): floorValue]
            modelGroups.last().modelWeight = modelWeight
        }
        floorsProvider.setResponse(accountId, floorsResponse)

        and: "PBS fetch rules from floors provider"
        cacheFloorsProviderRules(bidRequest, floorsPbsService, GENERIC, NONE)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        and: "PBS processes collected metrics request"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()

        then: "Bidder request bidFloor should not be passed"
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last()
        assert !bidderRequest.imp[0].bidFloor
        assert bidderRequest.ext?.prebid?.floors?.fetchStatus == ERROR

        and: "#FETCH_FAILURE_METRIC should be update"
        assert metrics[FETCH_FAILURE_METRIC] == 1

        and: "PBS log should contain error"
        def logs = floorsPbsService.getLogsByTime(startTime)
        def floorsLogs = getLogsByText(logs, "$BASIC_FETCH_URL$bidRequest.accountId")
        assert floorsLogs.size() == 1
        assert floorsLogs[0].contains(FETCHING_FLOORS_ERROR_LOG(bidRequest, MODEL_WEIGHT_INVALID.formatted(invalidModelWeight)))

        and: "Floors validation failure cannot reject the entire auction"
        assert !response.seatbid?.isEmpty()

        where:
        invalidModelWeight << [0, MAX_MODEL_WEIGHT + 1]
    }

    def "PBS should reject fetch when data skipRate from floors provider is invalid"() {
        given: "Test start time"
        def startTime = Instant.now()

        and: "Flush metrics"
        flushMetrics(floorsPbsService)

        and: "Default BidRequest"
        def bidRequest = BidRequest.defaultBidRequest

        and: "Account with enabled fetch, fetch.url in the DB"
        def accountId = bidRequest.accountId
        def account = getAccountWithEnabledFetch(accountId)
        accountDao.save(account)

        and: "Set Floors Provider response"
        def floorValue = PBSUtils.randomFloorValue
        def floorsResponse = PriceFloorData.priceFloorData.tap {
            modelGroups << FloorModelGroup.modelGroup
            modelGroups.first().values = [(rule): floorValue + 0.1]
            modelGroups[0].skipRate = 0
            skipRate = invalidSkipRate
            modelGroups.last().values = [(rule): floorValue]
            modelGroups.last().skipRate = 0
        }
        floorsProvider.setResponse(accountId, floorsResponse)

        and: "PBS fetch rules from floors provider"
        cacheFloorsProviderRules(bidRequest, floorsPbsService, GENERIC, NONE)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        and: "PBS processes collected metrics request"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()

        then: "Bidder request bidFloor should not be passed"
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last()
        assert !bidderRequest.imp[0].bidFloor
        assert bidderRequest.ext?.prebid?.floors?.fetchStatus == ERROR

        and: "#FETCH_FAILURE_METRIC should be update"
        assert metrics[FETCH_FAILURE_METRIC] == 1

        and: "PBS log should contain error"
        def message = "Price floor data skipRate must be in range(0-100), but was $invalidSkipRate"
        def logs = floorsPbsService.getLogsByTime(startTime)
        def floorsLogs = getLogsByText(logs, "$BASIC_FETCH_URL$bidRequest.accountId")
        assert floorsLogs.size() == 1
        assert floorsLogs[0].contains(FETCHING_FLOORS_ERROR_LOG(bidRequest, message))

        and: "Floors validation failure cannot reject the entire auction"
        assert !response.seatbid?.isEmpty()

        where:
        invalidSkipRate << [SKIP_RATE_MIN - 1, SKIP_RATE_MAX + 1]
    }

    def "PBS should reject fetch when modelGroup skipRate from floors provider is invalid"() {
        given: "Test start time"
        def startTime = Instant.now()

        and: "Flush metrics"
        flushMetrics(floorsPbsService)

        and: "Default BidRequest"
        def bidRequest = BidRequest.defaultBidRequest

        and: "Account with enabled fetch, fetch.url in the DB"
        def accountId = bidRequest.accountId
        def account = getAccountWithEnabledFetch(accountId)
        accountDao.save(account)

        and: "Set Floors Provider response"
        def floorValue = PBSUtils.randomFloorValue
        def floorsResponse = PriceFloorData.priceFloorData.tap {
            modelGroups << FloorModelGroup.modelGroup
            modelGroups.first().values = [(rule): floorValue + 0.1]
            modelGroups[0].skipRate = invalidSkipRate
            skipRate = 0
            modelGroups.last().values = [(rule): floorValue]
            modelGroups.last().skipRate = 0
        }
        floorsProvider.setResponse(accountId, floorsResponse)

        and: "PBS fetch rules from floors provider"
        cacheFloorsProviderRules(bidRequest, floorsPbsService, GENERIC, NONE)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        and: "PBS processes collected metrics request"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()

        then: "Bidder request bidFloor should not be passed"
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last()
        assert !bidderRequest.imp[0].bidFloor
        assert bidderRequest.ext?.prebid?.floors?.fetchStatus == ERROR

        and: "#FETCH_FAILURE_METRIC should be update"
        assert metrics[FETCH_FAILURE_METRIC] == 1

        and: "PBS log should contain error"
        def logs = floorsPbsService.getLogsByTime(startTime)
        def floorsLogs = getLogsByText(logs, "$BASIC_FETCH_URL$bidRequest.accountId")
        assert floorsLogs.size() == 1
        assert floorsLogs[0].contains(FETCHING_FLOORS_ERROR_LOG(bidRequest, SKIP_RATE_INVALID.formatted(invalidSkipRate)))

        and: "Floors validation failure cannot reject the entire auction"
        assert !response.seatbid?.isEmpty()

        where:
        invalidSkipRate << [SKIP_RATE_MIN - 1, SKIP_RATE_MAX + 1]
    }

    def "PBS should reject fetch when default floor value from floors provider is invalid"() {
        given: "Test start time"
        def startTime = Instant.now()

        and: "Flush metrics"
        flushMetrics(floorsPbsService)

        and: "Default BidRequest"
        def bidRequest = BidRequest.defaultBidRequest

        and: "Account with enabled fetch, fetch.url in the DB"
        def accountId = bidRequest.accountId
        def account = getAccountWithEnabledFetch(accountId)
        accountDao.save(account)

        and: "Set Floors Provider response"
        def floorValue = PBSUtils.randomFloorValue
        def invalidDefaultFloor = DEFAULT_FLOOR_VALUE_MIN - 1
        def floorsResponse = PriceFloorData.priceFloorData.tap {
            modelGroups << FloorModelGroup.modelGroup
            modelGroups.first().values = [(rule): floorValue + 0.1]
            modelGroups[0].defaultFloor = invalidDefaultFloor
            modelGroups.last().values = [(rule): floorValue]
            modelGroups.last().defaultFloor = DEFAULT_FLOOR_VALUE_MIN
        }
        floorsProvider.setResponse(accountId, floorsResponse)

        and: "PBS fetch rules from floors provider"
        cacheFloorsProviderRules(bidRequest, floorsPbsService, GENERIC, NONE)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        and: "PBS processes collected metrics request"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()

        then: "Bidder request bidFloor should not be passed"
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last()
        assert !bidderRequest.imp[0].bidFloor
        assert bidderRequest.ext?.prebid?.floors?.fetchStatus == ERROR

        and: "#FETCH_FAILURE_METRIC should be update"
        assert metrics[FETCH_FAILURE_METRIC] == 1

        and: "PBS log should contain error"
        def message = "Price floor modelGroup default must be positive float, but was $invalidDefaultFloor"
        def logs = floorsPbsService.getLogsByTime(startTime)
        def floorsLogs = getLogsByText(logs, "$BASIC_FETCH_URL$bidRequest.accountId")
        assert floorsLogs.size() == 1
        assert floorsLogs[0].contains(FETCHING_FLOORS_ERROR_LOG(bidRequest, message))

        and: "Floors validation failure cannot reject the entire auction"
        assert !response.seatbid?.isEmpty()
    }

    def "PBS should give preference to currency from modelGroups when signalling"() {
        given: "Default BidRequest with floors"
        def bidRequest = bidRequestWithFloors

        and: "Account with enabled fetch, fetch.url in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.accountId)
        accountDao.save(account)

        and: "Set Floors Provider response"
        def floorValue = PBSUtils.randomFloorValue
        def floorsResponse = PriceFloorData.priceFloorData.tap {
            modelGroups[0].values = [(rule): floorValue]
            modelGroups[0].currency = modelGroupCurrency
            currency = dataCurrency
        }
        floorsProvider.setResponse(bidRequest.accountId, floorsResponse)

        and: "PBS fetch rules from floors provider"
        cacheFloorsProviderRules(bidRequest)

        when: "PBS processes auction request"
        floorsPbsService.sendAuctionRequest(bidRequest)

        then: "Bidder request should contain bidFloorCur from floors provider according to priority"
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last()
        assert bidderRequest.imp[0].bidFloorCur == JPY

        where:
        modelGroupCurrency | dataCurrency
        JPY                | EUR
        null               | JPY
    }

    def "PBS should not contain errors when the header has Cache-Control with directives"() {
        given: "Test start time"
        def startTime = Instant.now()

        and: "Default BidRequest"
        def bidRequest = BidRequest.defaultBidRequest

        and: "Account with enabled fetch, fetch.url in the DB"
        def accountId = bidRequest.accountId
        def account = getAccountWithEnabledFetch(accountId)
        accountDao.save(account)

        and: "Set Floors Provider response with header Cache-Control and floor value"
        def header = ["Cache-Control": "no-cache, no-store, max-age=800, must-revalidate"]
        def floorValue = PBSUtils.randomFloorValue
        def floorsResponse = PriceFloorData.priceFloorData.tap {
            modelGroups[0].values = [(rule): floorValue]
        }
        floorsProvider.setResponse(accountId, floorsResponse, header)

        and: "PBS cache rules"
        cacheFloorsProviderRules(bidRequest, floorValue, floorsPbsService)

        when: "PBS processes auction request"
        floorsPbsService.sendAuctionRequest(bidRequest)

        then: "PBS log should not contain error"
        def logs = floorsPbsService.getLogsByTime(startTime)
        def floorsLogs = getLogsByText(logs, BASIC_FETCH_URL)
        assert floorsLogs.size() == 0

        and: "Bidder request should contain floors data from floors provider"
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last()
        assert bidderRequest.ext?.prebid?.floors?.fetchStatus == SUCCESS
        assert bidderRequest.ext?.prebid?.floors?.location == FETCH
    }

    def "PBS should always populate floors skipped flag when floors are enabled for account"() {
        given: "Default BidRequest"
        def bidRequest = BidRequest.defaultBidRequest

        and: "Account with enabled fetch, fetch.url in the DB"
        def accountId = bidRequest.accountId
        def account = getAccountWithEnabledFetch(accountId)
        accountDao.save(account)

        and: "Set Floors Provider response"
        def floorValue = PBSUtils.randomFloorValue
        def floorsResponse = PriceFloorData.priceFloorData.tap {
            modelGroups[0].values = [(rule): floorValue]
        }
        floorsProvider.setResponse(accountId, floorsResponse)

        and: "PBS cache rules"
        cacheFloorsProviderRules(bidRequest, floorValue, floorsPbsService)

        when: "PBS processes auction request"
        floorsPbsService.sendAuctionRequest(bidRequest)

        then: "Bidder request skipped flag should be false"
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last()
        verifyAll {
            bidderRequest.ext?.prebid?.floors?.skipped == false

            bidderRequest.ext.prebid.floors?.fetchStatus == SUCCESS
            bidderRequest.ext.prebid.floors?.location == FETCH
        }
    }

    def "PBS should populate floors enabled = true when floors skip rate 100"() {
        given: "Default BidRequest with floors"
        def floorsSkipRate = 100
        def floorsEnabled = true
        def bidRequest = BidRequest.defaultBidRequest.tap {
            ext.prebid.floors = new ExtPrebidFloors(enabled: floorsEnabled, skipRate: floorsSkipRate)
        }

        and: "Account with enabled fetch, fetch.url in the DB"
        def accountId = bidRequest.accountId
        def account = getAccountWithEnabledFetch(accountId)
        accountDao.save(account)

        and: "Set Floors Provider response"
        def floorValue = PBSUtils.randomFloorValue
        def floorsResponse = PriceFloorData.priceFloorData.tap {
            modelGroups[0].values = [(rule): floorValue]
        }
        floorsProvider.setResponse(accountId, floorsResponse)

        and: "PBS fetch rules from floors provider"
        cacheFloorsProviderRules(bidRequest)

        when: "PBS processes auction request"
        floorsPbsService.sendAuctionRequest(bidRequest)

        then: "Bidder request should contain floors data"
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last()
        verifyAll {
            bidderRequest.ext?.prebid?.floors?.enabled == floorsEnabled
            bidderRequest.ext.prebid.floors?.skipRate == floorsSkipRate
            bidderRequest.ext.prebid.floors?.skipped == true

            bidderRequest.ext.prebid.floors?.fetchStatus == SUCCESS
            bidderRequest.ext.prebid.floors?.location == FETCH
        }
    }

    def "PBS shouldn't populate floors except initial field when floors enabled = false"() {
        given: "Default BidRequest with floors"
        def floorsSkipRate = 100
        def floorsEnabled = false
        def bidRequest = BidRequest.defaultBidRequest.tap {
            ext.prebid.floors = new ExtPrebidFloors(enabled: floorsEnabled, skipRate: floorsSkipRate)
        }

        and: "Account with enabled fetch, fetch.url in the DB"
        def accountId = bidRequest.accountId
        def account = getAccountWithEnabledFetch(accountId)
        accountDao.save(account)

        and: "Set Floors Provider response"
        def floorValue = PBSUtils.randomFloorValue
        def floorsResponse = PriceFloorData.priceFloorData.tap {
            modelGroups[0].values = [(rule): floorValue]
        }
        floorsProvider.setResponse(accountId, floorsResponse)

        and: "PBS fetch rules from floors provider"
        cacheFloorsProviderRules(bidRequest)

        when: "PBS processes auction request"
        floorsPbsService.sendAuctionRequest(bidRequest)

        then: "Bidder request shouldn't contain floors data, except initial field"
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last()
        verifyAll {
            bidderRequest.ext?.prebid?.floors?.enabled == floorsEnabled
            bidderRequest.ext.prebid.floors?.skipRate == floorsSkipRate
            bidderRequest.ext.prebid.floors?.skipped == null

            !bidderRequest.ext.prebid.floors?.fetchStatus
            !bidderRequest.ext.prebid.floors?.location
        }
    }

    def "PBS should validate fetch.max-schema-dims from account config and not reject entire auction"() {
        given: "Default BidRequest"
        def bidRequest = BidRequest.defaultBidRequest

        and: "Account with enabled fetch, maxSchemaDims in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.accountId).tap {
            config.auction.priceFloors.fetch.maxSchemaDims = maxSchemaDims
            config.auction.priceFloors.fetch.maxSchemaDimsSnakeCase = maxSchemaDimsSnakeCase
        }
        accountDao.save(account)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        then: "Metric alerts.account_config.ACCOUNT.price-floors should be update"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()
        assert metrics[INVALID_CONFIG_METRIC(bidRequest.accountId) as String] == 1

        and: "PBS floors validation failure should not reject the entire auction"
        assert !response.seatbid?.isEmpty()

        where:
        maxSchemaDims                 | maxSchemaDimsSnakeCase
        null                          | PBSUtils.randomNegativeNumber
        null                          | PBSUtils.getRandomNumber(20)
        PBSUtils.randomNegativeNumber | null
        PBSUtils.getRandomNumber(20)  | null
    }

    def "PBS should validate price-floor.max-rules from account config and not reject entire auction"() {
        given: "Default BidRequest"
        def bidRequest = BidRequest.defaultBidRequest

        and: "Account with enabled fetch, maxRules in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.accountId).tap {
            config.auction.priceFloors.maxRules = maxRules
            config.auction.priceFloors.maxRulesSnakeCase = maxRulesSnakeCase
        }
        accountDao.save(account)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        then: "Metric alerts.account_config.ACCOUNT.price-floors should be update"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()
        assert metrics[INVALID_CONFIG_METRIC(bidRequest.accountId) as String] == 1

        and: "PBS floors validation failure should not reject the entire auction"
        assert !response.seatbid?.isEmpty()

        where:
        maxRules                      | maxRulesSnakeCase
        null                          | PBSUtils.randomNegativeNumber
        PBSUtils.randomNegativeNumber | null
    }

    def "PBS should validate price-floor.max-schema-dims from account config and not reject entire auction"() {
        given: "Default BidRequest"
        def bidRequest = BidRequest.defaultBidRequest

        and: "Account with enabled fetch, maxSchemaDims in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.accountId).tap {
            config.auction.priceFloors.maxSchemaDims = maxSchemaDims
            config.auction.priceFloors.maxSchemaDimsSnakeCase = maxSchemaDimsSnakeCase
        }
        accountDao.save(account)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        then: "Metric alerts.account_config.ACCOUNT.price-floors should be update"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()
        assert metrics[INVALID_CONFIG_METRIC(bidRequest.accountId) as String] == 1

        and: "PBS floors validation failure should not reject the entire auction"
        assert !response.seatbid?.isEmpty()

        where:
        maxSchemaDims                 | maxSchemaDimsSnakeCase
        null                          | PBSUtils.randomNegativeNumber
        null                          | PBSUtils.getRandomNumber(20)
        PBSUtils.randomNegativeNumber | null
        PBSUtils.getRandomNumber(20)  | null
    }

    static int convertKilobyteSizeToByte(int kilobyteSize) {
        kilobyteSize * 1024
    }
}
