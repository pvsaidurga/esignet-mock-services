package io.mosip.esignet.mock.identitysystem.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.RSAKey;
import io.mosip.esignet.mock.identitysystem.dto.*;
import io.mosip.esignet.mock.identitysystem.entity.KycAuth;
import io.mosip.esignet.mock.identitysystem.exception.MockIdentityException;
import io.mosip.esignet.mock.identitysystem.repository.AuthRepository;
import io.mosip.esignet.mock.identitysystem.service.AuthenticationService;
import io.mosip.esignet.mock.identitysystem.service.IdentityService;
import io.mosip.esignet.mock.identitysystem.util.HelperUtil;
import io.mosip.kernel.core.util.StringUtils;
import io.mosip.kernel.signature.dto.JWTSignatureRequestDto;
import io.mosip.kernel.signature.dto.JWTSignatureResponseDto;
import io.mosip.kernel.signature.service.SignatureService;
import lombok.extern.slf4j.Slf4j;
import org.jose4j.jwe.ContentEncryptionAlgorithmIdentifiers;
import org.jose4j.jwe.JsonWebEncryption;
import org.jose4j.jwe.KeyManagementAlgorithmIdentifiers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import static io.mosip.esignet.mock.identitysystem.util.Constants.APPLICATION_ID;
import static io.mosip.esignet.mock.identitysystem.util.HelperUtil.ALGO_SHA3_256;


@Service
@Slf4j
public class AuthenticationServiceImpl implements AuthenticationService {

    private static final String PSUT_FORMAT = "%s%s";
    private static final String OTP_VALUE = "111111";

    @Autowired
    private AuthRepository authRepository;

    @Autowired
    private IdentityService identityService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SignatureService signatureService;

    @Value("${mosip.mock.ida.kyc.transaction-timeout-secs:60}")
    private int transactionTimeoutInSecs;

    @Value("${mosip.mock.ida.kyc.encrypt:false}")
    private boolean encryptKyc;

    @Value("${mosip.mock.ida.kyc.default-language:eng}")
    private String defaultLanguage;

    @Value("${mosip.esignet.mock.authenticator.ida.otp-channels}")
    private List<String> otpChannels;

    ArrayList<String> trnHash = new ArrayList<>();

    @Override
    public KycAuthResponseDto kycAuth(String relyingPartyId, String clientId, KycAuthRequestDto kycAuthRequestDto) throws MockIdentityException {
        //TODO validate relying party Id and client Id

        IdentityData identityData = identityService.getIdentity(kycAuthRequestDto.getIndividualId());
        if (identityData == null) {
            throw new MockIdentityException("invalid_individual_id");
        }
        boolean authStatus = false;
        if (kycAuthRequestDto.getOtp() != null) {
            //check if the trn is available and active
            if (StringUtils.isEmpty(kycAuthRequestDto.getTransactionId())) {
                log.error("Invalid transaction Id");
                throw new MockIdentityException("invalid_transaction_id");
            }

            var trn_hash = HelperUtil.generateB64EncodedHash(ALGO_SHA3_256,
                    String.format(kycAuthRequestDto.getTransactionId(), kycAuthRequestDto.getIndividualId(), OTP_VALUE));

            var isValid = trnHash.contains(trn_hash);
            if (isValid) {
                authStatus = kycAuthRequestDto.getOtp().equals(OTP_VALUE);
                if (authStatus)
                    trnHash.remove(trn_hash);
                else
                    throw new MockIdentityException("auth_failed");
            } else {
                throw new MockIdentityException("invalid_transaction");
            }
        }

        if (kycAuthRequestDto.getPin() != null) {
            authStatus = kycAuthRequestDto.getPin().equals(identityData.getPin());
            if (!authStatus)
                throw new MockIdentityException("auth_failed");
        }

        if (kycAuthRequestDto.getBiometrics() != null) {
            authStatus = true; //TODO
        }

        if (!CollectionUtils.isEmpty(kycAuthRequestDto.getTokens())) {
            authStatus = !StringUtils.isEmpty(kycAuthRequestDto.getTokens().get(0));
            if (!authStatus)
                throw new MockIdentityException("auth_failed");
        }


        KycAuth kycAuth = saveKycAuthTransaction(kycAuthRequestDto.getTransactionId(), relyingPartyId,
                kycAuthRequestDto.getIndividualId());

        KycAuthResponseDto kycAuthResponseDto = new KycAuthResponseDto();
        kycAuthResponseDto.setAuthStatus(authStatus);
        kycAuthResponseDto.setKycToken(kycAuth.getKycToken());
        kycAuthResponseDto.setPartnerSpecificUserToken(kycAuth.getPartnerSpecificUserToken());
        return kycAuthResponseDto;
    }

    @Override
    public KycExchangeResponseDto kycExchange(String relyingPartyId, String clientId, KycExchangeRequestDto kycExchangeRequestDto) throws MockIdentityException {
        //TODO validate relying party Id and client Id
        Optional<KycAuth> result = authRepository.findByKycTokenAndValidityAndTransactionIdAndIndividualId
                (kycExchangeRequestDto.getKycToken(), Valid.ACTIVE, kycExchangeRequestDto.getTransactionId(),
                        kycExchangeRequestDto.getIndividualId());

        if (!result.isPresent())
            throw new MockIdentityException("mock-ida-006");

        LocalDateTime savedTime = result.get().getResponseTime();
        long seconds = savedTime.until(kycExchangeRequestDto.getRequestDateTime(), ChronoUnit.SECONDS);
        if (seconds < 0 || seconds > transactionTimeoutInSecs) {
            result.get().setValidity(Valid.EXPIRED);
            authRepository.save(result.get());
            throw new MockIdentityException("mock-ida-007");
        }

        try {
            Map<String, Object> kyc = buildKycDataBasedOnPolicy(kycExchangeRequestDto.getIndividualId(),
                    kycExchangeRequestDto.getAcceptedClaims(), kycExchangeRequestDto.getClaimLocales());
            kyc.put("sub", result.get().getPartnerSpecificUserToken());

            result.get().setValidity(Valid.PROCESSED);
            authRepository.save(result.get());

            String finalKyc = this.encryptKyc ? getJWE(relyingPartyId, signKyc(kyc)) : signKyc(kyc);
            KycExchangeResponseDto kycExchangeResponseDto = new KycExchangeResponseDto();
            kycExchangeResponseDto.setKyc(finalKyc);
            return kycExchangeResponseDto;
        } catch (Exception ex) {
            log.error("Failed to build kyc data", ex);
            throw new MockIdentityException("mock-ida-008");
        }
    }

    @Override
    public SendOtpResult sendOtp(String relyingPartyId, String clientId, SendOtpDto sendOtpDto) throws MockIdentityException {
        //TODO validate relying party Id and client Id

        IdentityData identityData = identityService.getIdentity(sendOtpDto.getIndividualId());
        if (identityData == null) {
            log.error("Provided individual Id not found {}", sendOtpDto.getIndividualId());
            throw new MockIdentityException("invalid_individual_id");
        }

        if (!sendOtpDto.getOtpChannels().stream().allMatch(this::isSupportedOtpChannel)) {
            log.error("Invalid Otp Channels");
            throw new MockIdentityException("invalid_otp_channel");
        }

        String maskedEmailId = null;
        String maskedMobile = null;
        for (String channel : sendOtpDto.getOtpChannels()) {
            if (channel.equalsIgnoreCase("email")) {
                maskedEmailId = HelperUtil.maskEmail(identityData.getEmail());
            }
            if (channel.equalsIgnoreCase("phone") || channel.equalsIgnoreCase("mobile")) {
                maskedMobile = HelperUtil.maskMobile(identityData.getPhone());
            }
        }

        if(org.springframework.util.StringUtils.isEmpty(maskedEmailId) &&
                org.springframework.util.StringUtils.isEmpty(maskedMobile)) {
            log.error("neither email id nor mobile number found for the given individualId");
            throw new MockIdentityException("no_email_mobile_found");
        }

        var trn_token_hash = HelperUtil.generateB64EncodedHash(ALGO_SHA3_256,
                String.format(sendOtpDto.getTransactionId(), sendOtpDto.getIndividualId(), OTP_VALUE));

        trnHash.add(trn_token_hash);
        return new SendOtpResult(sendOtpDto.getTransactionId(), maskedEmailId, maskedMobile);
    }

    private String signKyc(Map<String, Object> kyc) throws JsonProcessingException {
        String payload = objectMapper.writeValueAsString(kyc);
        JWTSignatureRequestDto jwtSignatureRequestDto = new JWTSignatureRequestDto();
        jwtSignatureRequestDto.setApplicationId(APPLICATION_ID);
        jwtSignatureRequestDto.setReferenceId("");
        jwtSignatureRequestDto.setIncludePayload(true);
        jwtSignatureRequestDto.setIncludeCertificate(false);
        jwtSignatureRequestDto.setDataToSign(HelperUtil.b64Encode(payload));
        jwtSignatureRequestDto.setIncludeCertHash(false);
        JWTSignatureResponseDto responseDto = signatureService.jwtSign(jwtSignatureRequestDto);
        return responseDto.getJwtSignedData();
    }

    private String getJWE(String relyingPartyId, String signedJwt) throws Exception {
        JsonWebEncryption jsonWebEncryption = new JsonWebEncryption();
        jsonWebEncryption.setAlgorithmHeaderValue(KeyManagementAlgorithmIdentifiers.RSA_OAEP_256);
        jsonWebEncryption.setEncryptionMethodHeaderParameter(ContentEncryptionAlgorithmIdentifiers.AES_256_GCM);
        jsonWebEncryption.setPayload(signedJwt);
        jsonWebEncryption.setContentTypeHeaderValue("JWT");
        RSAKey rsaKey = getRelyingPartyPublicKey(relyingPartyId);
        jsonWebEncryption.setKey(rsaKey.toPublicKey());
        jsonWebEncryption.setKeyIdHeaderValue(rsaKey.getKeyID());
        return jsonWebEncryption.getCompactSerialization();
    }

    private RSAKey getRelyingPartyPublicKey(String relyingPartyId) {
        //TODO where to store relying-party public key
        throw new MockIdentityException("jwe-not-implemented");
    }

    private KycAuth saveKycAuthTransaction(String transactionId, String relyingPartyId, String individualId) {
        String kycToken = HelperUtil.generateB64EncodedHash(ALGO_SHA3_256, UUID.randomUUID().toString());
        String psut;
        try {
            psut = HelperUtil.generateB64EncodedHash(ALGO_SHA3_256,
                    String.format(PSUT_FORMAT, individualId, relyingPartyId));
        } catch (Exception e) {
            log.error("Failed to generate PSUT", e);
            throw new MockIdentityException("mock-ida-004");
        }

        KycAuth kycAuth = new KycAuth(kycToken, psut, LocalDateTime.now(ZoneOffset.UTC), Valid.ACTIVE, transactionId,
                individualId);
        if (kycAuth == null)
            throw new MockIdentityException("mock-ida-005");
        return authRepository.save(kycAuth);
    }

    private Map<String, Object> buildKycDataBasedOnPolicy(String individualId, List<String> claims, List<String> locales) {
        Map<String, Object> kyc = new HashMap<>();
        IdentityData identityData = identityService.getIdentity(individualId);
        if (identityData == null) {
            throw new MockIdentityException("mock-ida-001");
        }

        if (CollectionUtils.isEmpty(locales)) {
            locales = Arrays.asList(defaultLanguage);
        }
        boolean singleLanguage = locales.size() == 1;
        for (String claim : claims) {
            switch (claim) {
                case "name":
                    kyc.putAll(getKycValues(locales, "name", identityData.getFullName(), singleLanguage));
                    break;
                case "birthdate":
                    if (identityData.getDateOfBirth() != null) {
                        kyc.put("birthdate", identityData.getDateOfBirth());
                    }
                    break;
                case "gender":
                    kyc.putAll(getKycValues(locales, "gender", identityData.getGender(), singleLanguage));
                    break;
                case "email":
                    if (identityData.getEmail() != null) {
                        kyc.put("email", identityData.getEmail());
                    }
                    break;
                case "phone_number":
                    if (identityData.getPhone() != null) {
                        kyc.put("phone_number", identityData.getPhone());
                    }
                    break;
                case "address":
                    Map<String, Object> addressValues = new HashMap<>();
                    addressValues.putAll(getKycValues(locales, "street_address", identityData.getStreetAddress(), singleLanguage));
                    addressValues.putAll(getKycValues(locales, "locality", identityData.getLocality(), singleLanguage));
                    addressValues.putAll(getKycValues(locales, "region", identityData.getRegion(), singleLanguage));
                    if (identityData.getPostalCode() != null) {
                        addressValues.put("postal_code", identityData.getPostalCode());
                    }
                    addressValues.putAll(getKycValues(locales, "country", identityData.getCountry(), singleLanguage));
                    kyc.put("address", addressValues);
                    break;
                case "picture":
                    if (identityData.getEncodedPhoto() != null) {
                        kyc.put("picture", identityData.getEncodedPhoto());
                    }
                    break;
                case "individual_id":
                    if (identityData.getIndividualId() != null) {
                        kyc.put("individual_id", identityData.getIndividualId());
                    }
                    break;
            }
        }
        return kyc;
    }

    private Map<String, Object> getKycValues(List<String> locales, String claimName, List<LanguageValue> values, boolean isSingleLanguage) {
        if (values == null) {
            return Collections.emptyMap();
        }
        for (String locale : locales) {
            return values.stream()
                    .filter(v -> v.getLanguage().equalsIgnoreCase(locale) || v.getLanguage().startsWith(locale))
                    .collect(Collectors.toMap(v -> isSingleLanguage ? claimName : claimName + "#" + locale, v -> v.getValue()));
        }
        return Collections.emptyMap();
    }

    public boolean isSupportedOtpChannel(String channel) {
        return channel != null && otpChannels.contains(channel.toLowerCase());
    }
}
