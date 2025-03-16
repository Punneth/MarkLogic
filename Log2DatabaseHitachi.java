package in.atomtech.mw.transaction.hitachi;

import java.io.Serializable;


import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Objects;
import java.util.Properties;
import java.util.TimeZone;

import org.jpos.core.Card;
import org.jpos.core.Configurable;
import org.jpos.core.Configuration;
import org.jpos.core.ConfigurationException;
import org.jpos.ee.DB;
import org.jpos.iso.ISOCurrency;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.ISOUtil;
import org.jpos.transaction.AbortParticipant;
import org.jpos.transaction.Context;
import org.jpos.util.Log;
import org.jpos.util.NameRegistrar;
import org.json.JSONObject;

import in.atomtech.mw.EFSCTXConstants;
import in.atomtech.mw.EFSMessageStatus;
import in.atomtech.mw.EFSReversalType;
import in.atomtech.mw.database.Acquirer;
import in.atomtech.mw.database.EMITransactionData;
import in.atomtech.mw.database.Issuer;
import in.atomtech.mw.database.Transaction;
import in.atomtech.mw.database.TransactionCompositeKey;
import in.atomtech.mw.security.EFSCrypto;
import in.atomtech.mw.transaction.middleware.GenerateCardToken;


public class Log2DatabaseHitachi implements Configurable, AbortParticipant {

	//TransactionDaoImpl transactionDaoImpl= new TransactionDaoImpl();
	@SuppressWarnings("unused")
	private String scheme;
	private Log log = org.jpos.util.Log.getLog("Q2", this.getClass().getName());

	private RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;
	private int DB_TIMEOUT_IN_SECONDS = 300;

	@Override
	public int prepare(long id, Serializable context) {
		return PREPARED | READONLY;
	}

	public int prepareForAbort(long id, Serializable o) {
		return PREPARED | READONLY;
	}

	public void commit(long id, Serializable context) {
		logToDatabase(id, (Context) context);
	}

	public void abort(long id, Serializable context) {
		logToDatabase(id, (Context) context);
	}

	private String getSettlementDate(Context ctx) {
		
		log.info("Inside Log2DatabaseHitachi");
		
		Acquirer acquirer = (Acquirer) ctx.get(EFSCTXConstants.ACQUIRER);
		DateFormat timeFormat = new SimpleDateFormat("HHmm");
		DateFormat mdFormat = new SimpleDateFormat("MMdd");
		String settlementDate;
		Date dCur = new Date();
		String tCur = timeFormat.format(dCur);
		String mdCur = mdFormat.format(dCur);

		if (acquirer == null) {
			return mdCur;
		}
		/*
		 * mdAcq could be null if never set by any cutover message from scheme. mdAcq
		 * could be today's value or previous days value if it was set by past cutover
		 * message from scheme. mdAcq could be tomorrow's value if it was set today by a
		 * cutover message
		 */
		String mdAcq = acquirer.getSettlementDate();

		if ((mdAcq != null) && (mdAcq.compareTo(mdCur) > 0)) {// set today by scheme cutover
			settlementDate = mdAcq;
		} else if (tCur.compareTo(acquirer.getCutoverDefaultTime()) >= 0) {// current time>=default cutover time
			Calendar c = Calendar.getInstance();
			c.setTime(dCur);
			c.add(Calendar.DATE, 1);
			dCur = c.getTime();
			settlementDate = mdFormat.format(dCur);
		} else {// current time < default cutover time AND no cutover message received today
			settlementDate = mdFormat.format(dCur);
		}
		log.info("Inside Log2DatabaseHitachi");
		return settlementDate;
	}

	private void logToDatabase(long id, Context ctx) {
		int messageProcessingStatus = (int) ctx.get(EFSCTXConstants.MESSAGE_PROCESSING_STATUS);
		if (messageProcessingStatus < EFSMessageStatus.MESSAGE_SENT_TO_SCHEME) {// Could not be sent to scheme
			return;// We don't log this transaction
		}

		ISOMsg req2Scheme = (ISOMsg) ctx.get(EFSCTXConstants.MESSAGE_TO_SCHEME);

		if (req2Scheme == null) {// Validation error before preparing for scheme
			return;// We don't log this transaction
		}
		
		

		// DB db = (DB) ctx.get (DB);
		DB db = new DB();// Keshaw
		ISOMsg reqFromPOS = (ISOMsg) ctx.get(EFSCTXConstants.MESSAGE_FROM_SOURCE);
		ISOMsg response = (ISOMsg) ctx.get(EFSCTXConstants.MESSAGE_FROM_SCHEME);
		//Terminal terminal = (Terminal) ctx.get(EFSCTXConstants.TERMINAL);
		JSONObject terminaldata = new JSONObject((String) ctx.get(EFSCTXConstants.TERMINAL_DATA_FROM_TITAN));

		Acquirer acquirer = (Acquirer) ctx.get(EFSCTXConstants.ACQUIRER);
		//Issuer issuer = (Issuer) ctx.get(EFSCTXConstants.ISSUER);
		JSONObject issuerdata = new JSONObject((String) ctx.get(EFSCTXConstants.TITAN_ISSUER));
		JSONObject merchantdata = new JSONObject((String) ctx.get(EFSCTXConstants.MERCHANT_DATA_FROM_TITAN));
		String tracerId = ctx.get(EFSCTXConstants.TRACER_ID);
		
		Card card = (Card) ctx.get(EFSCTXConstants.CARD);

		String currencyTransaction = reqFromPOS.hasField(49) ? reqFromPOS.getString(49)
				: acquirer.getAcquiringInstitutionCurrencyCodeNumeric();

		Transaction tx;
		TransactionCompositeKey tc;
		Properties EFSGlobalData;

		try {
			db.open();
			db.beginTransaction(DB_TIMEOUT_IN_SECONDS);// Keshaw

			EFSGlobalData = (Properties) NameRegistrar.get("EFSGlobalData");
			tc = new TransactionCompositeKey(Integer.valueOf(terminaldata.getString("atomTID")),
					req2Scheme.getString(13), reqFromPOS.getString(12), req2Scheme.getString(11));

			//added by mayur pawar
			log.info("object of req2Scheme : "+reqFromPOS);
			log.info("Time received in fields from log2databasehitachi :"+reqFromPOS.getString(12));


			// Check if the Transaction is already there in session from previous queries
			if ((tx = db.session().find(Transaction.class, tc)) == null) {
				tx = new Transaction();
				tx.setTransactionCompositeKey(tc);
			}
			// Req2Scheme MTI = 0400/0420 = POS Void, POS Reversal, Switch Reversal
			// if(req2Scheme.getMTI().startsWith("04")) {
			String txnName = ctx.getString(EFSCTXConstants.TRANSACTION_NAME);
			if (txnName.startsWith("4")) {
				if (response != null) {
					// String txnName = ctx.getString(EFSCTXConstants.TRANSACTION_NAME);
					if (txnName.startsWith("420.")) {// POS Reversal
						tx.setVoidReversalIndicator(EFSReversalType.POS_REVERSAL);
					} else if (txnName.startsWith("400.")) {// POS Void
						tx.setVoidReversalIndicator(EFSReversalType.POS_VOID);
					} else if ("420".equals(txnName)) {// Switch Reversal
						tx.setVoidReversalIndicator(EFSReversalType.SWITCH_REVERSAL);
					}
				} else {// Already sent to SAF
					tx = db.session().get(Transaction.class, tc);// Double sure to get the persistent object
					if (tx.getVoidReversalIndicator() == null) {// Check if SAF has not already processed it
						tx.setVoidReversalIndicator(EFSReversalType.SAF_FLIGHT);
					}

				}
				tx.setReversalResponseCode(req2Scheme.getString(39));
				
				ctx.put(EFSCTXConstants.TITAN_TRANSACTION_DATA, tx);
				ctx.put(EFSCTXConstants.TITAN_TRANSACTION_COMPOSITEKEY, tc);
				
				db.session().update(tx);
				db.commit();
			} else if (req2Scheme.getMTI().startsWith("022012345")) {// Pre-Auth Completion
				tx.setCurrencyCodeTransaction(currencyTransaction);
				BigDecimal amountTransaction = ISOCurrency
						.parseFromISO87String(req2Scheme.getString(4), currencyTransaction).setScale(2, ROUNDING_MODE);
				tx.setAmountTransaction(amountTransaction);
				// additionalAmounts
				if (reqFromPOS.hasField(54)) {
					String additionalAmount = reqFromPOS.getString(54);
					tx.setAdditionalAmounts(ISOCurrency.parseFromISO87String(additionalAmount, currencyTransaction)
							.setScale(2, ROUNDING_MODE));
				}
				// Batch Number (Required for batch closure matching)
				if (reqFromPOS.hasField(56)) {
					tx.setBatchNumber(reqFromPOS.getString(56));
				}

				// Store completion response code in reversalResponseCode field
				if (response != null && response.hasField(39)) {
					tx.setReversalResponseCode(response.getString(39));
				}
				// How do I change the date of pre-auth completion???
				db.session().update(tx);
				db.commit();
			} else {// Req2Scheme MTI = 0100/0200
				tx.setBankCode(acquirer.getAcquirerCompositeKey().getBankCode());
				//tx.setSchemeName(issuer.getSchemeName());

				//for reversal txn below data is null @29032022 by MP
				if(Objects.nonNull(issuerdata))
				{
					tx.setSchemeName(issuerdata.getString("schemeName"));
					tx.setSMSDMSIndicator(issuerdata.getString("messageType"));
					tx.setCardType(issuerdata.getString("cardType"));
				}
				
				tx.setGatewayName(acquirer.getGatewayName());
				tx.setPosMTI(reqFromPOS.getMTI());
				// Added for Transaction Types
				tx.setPosTxnType(reqFromPOS.getString(3).substring(0, 2));
				tx.setSchemeMTI(req2Scheme.getMTI());
				// PAN Masking required, protect(s) masks with '_'.
				tx.setMaskedPAN(ISOUtil.protect(card.getPan(), '*'));
				
				tx.setProcessingCode(reqFromPOS.getString(3));  //changing proc code as original

				tx.setCurrencyCodeTransaction(currencyTransaction);
				BigDecimal amountTransaction = ISOCurrency
						.parseFromISO87String(req2Scheme.getString(4), currencyTransaction).setScale(2, ROUNDING_MODE);
				tx.setAmountTransaction(amountTransaction);
				if (response != null) { // For settlement currency and amount
					if (response.hasField(50) && response.hasField(5)) {// BigDecimal scale to 2
						String currencySettlement = response.getString(50);
						tx.setCurrencyCodeSettlement(currencySettlement);
						tx.setAmountSettlement(
								ISOCurrency.parseFromISO87String(response.getString(5), currencySettlement).setScale(2,
										ROUNDING_MODE));
					} else {// Before DCC
						tx.setCurrencyCodeSettlement(currencyTransaction);
						tx.setAmountSettlement(amountTransaction);
					}
					if (response.hasField(9)) {// BigDecimal scale to 5
						tx.setConversionRateSettlement(parseAmountConversionRate(response.getString(9), 5));
					} else {// Before DCC
						tx.setConversionRateSettlement(new BigDecimal("1.00000"));
					}
				}

				// tx.setTransmissionDateTime(req2Scheme.getString(7));
				Date d = (Date) ctx.get(EFSCTXConstants.MESSAGE_ENTRY_TIMESTAMP);
				SimpleDateFormat df = new SimpleDateFormat("MMddHHmmss");
				df.setTimeZone(TimeZone.getTimeZone("UTC"));
				tx.setTransmissionDateTime(df.format(d));

				//tx.setPosTime(req2Scheme.getString(12));

				String encryptedExpiryDate = ctx.getString(EFSCTXConstants.ENCRYPTED_EXPIRY_DATE);
				if (encryptedExpiryDate != null) {
					tx.setEncryptedExpiryDate(encryptedExpiryDate);
				} else {
					String DE14 = card.getExp();
					if (DE14 != null) {
						encryptedExpiryDate = EFSCrypto.encrypt3DES(DE14);
					}
				}

				// Settlement Date logic -> Save the one from response else the calculated one.
				if (response != null && response.hasField(15)) {
					tx.setSettlementDate(response.getString(15));
				} else {
					tx.setSettlementDate(getSettlementDate(ctx));
				}
				//				tx.setMcc(terminal.getMerchant().getMCC());
				tx.setMcc(merchantdata.getString("mccCode"));

				tx.setAcquiringInstitutionCountryCodeNumeric(acquirer.getAcquiringInstitutionCountryCodeNumeric());
				if (req2Scheme.hasField(22)) {// POS Entry mode
					tx.setPosEntryMode(req2Scheme.getString(22));
				} else {
					tx.setPosEntryMode(reqFromPOS.getString(22));
				}
				if (reqFromPOS.hasField(23)) {// Card Sequence Number
					tx.setPanSequenceNumber(reqFromPOS.getString(23));
				}
				if (req2Scheme.hasField(25)) {// POS Condition Code
					tx.setPosConditionCode(req2Scheme.getString(25));
				} else {
					tx.setPosConditionCode(reqFromPOS.getString(25));
				}

				// Start Transaction Fee
				BigDecimal fee = new BigDecimal("0.00");// Default value of 0.00
				char sign = 'C';// Credit
				if (req2Scheme.hasField(28)) {
					fee = ISOCurrency.parseFromISO87String(req2Scheme.getString(28).substring(1), currencyTransaction)
							.setScale(2, ROUNDING_MODE);
					sign = req2Scheme.getString(28).charAt(0);
				} else if (reqFromPOS.hasField(28)) {
					fee = ISOCurrency.parseFromISO87String(reqFromPOS.getString(28).substring(1), currencyTransaction)
							.setScale(2, ROUNDING_MODE);
					sign = reqFromPOS.getString(28).charAt(0);
				}
				if (sign == 'D' || sign == 'd') {// Debit
					fee = fee.negate();
				}
				tx.setAmountTransactionFee(fee);
				// End Transaction Fee

				if (response != null && response.hasField(37)) {
					tx.setRrn(response.getString(37));
				} else if (req2Scheme.hasField(37)) {
					tx.setRrn(req2Scheme.getString(37));
				} else if (reqFromPOS.hasField(37)) {
					tx.setRrn(reqFromPOS.getString(37));
				}

				if (response != null && response.hasField(38)) {
					tx.setAuthorisationIdentificationResponse(response.getString(38));
				} else if (req2Scheme.hasField(38)) {
					tx.setAuthorisationIdentificationResponse(req2Scheme.getString(38));
				} else if (reqFromPOS.hasField(38)) {
					tx.setAuthorisationIdentificationResponse(reqFromPOS.getString(38));
				}
				if (response != null && response.hasField(39)) {
					tx.setResponseCode(response.getString(39));
					if (response.hasField(44)) {// Additional response during rejection
						tx.setAdditionalResponseData(response.getString(44));
					}
				} else if (ctx.getString(EFSCTXConstants.SWITCH_ERROR_CODE) == null
						&& reqFromPOS.getString(3).startsWith("20")) {// Refund
					tx.setResponseCode("00");
				} else if(ctx.getString(EFSCTXConstants.SWITCH_ERROR_CODE) ==null){
					tx.setResponseCode("99");
					tx.setResponseCode(ctx.getString(EFSCTXConstants.SWITCH_ERROR_CODE));
					tx.setAdditionalResponseData(ctx.getString(EFSCTXConstants.SWITCH_ERROR_CODE_DESCRIPTION));
				}
				
				else {
					tx.setResponseCode(ctx.getString(EFSCTXConstants.SWITCH_ERROR_CODE));
					tx.setAdditionalResponseData(ctx.getString(EFSCTXConstants.SWITCH_ERROR_CODE_DESCRIPTION));
				}

				if (response != null && response.hasField(40)) {
					tx.setServiceRestrictionCode(response.getString(40));
				} else if (req2Scheme.hasField(40)) {
					tx.setServiceRestrictionCode(req2Scheme.getString(40));
				} else if (reqFromPOS.hasField(40)) {
					tx.setServiceRestrictionCode(reqFromPOS.getString(40));
				} else {
					tx.setServiceRestrictionCode(card.getServiceCode());
				}
				tx.setTid(reqFromPOS.getString(41));
				tx.setMid(reqFromPOS.getString(42));

				// additionaDataDE48
				// Value from scheme or sent to scheme or received from POS.
				// Visa doesn't use it
				if (response != null && response.hasField(48)) {
					tx.setAdditionaDataDE48(response.getString(48));
				} else if (req2Scheme.hasField(48)) {
					tx.setAdditionaDataDE48(req2Scheme.getString(48));
				} else if (reqFromPOS.hasField(48)) {
					tx.setAdditionaDataDE48(reqFromPOS.getString(48));
				}

				// additionalAmounts
				if (reqFromPOS.hasField(54)) {
					String additionalAmount = reqFromPOS.getString(54);
					tx.setAdditionalAmounts(ISOCurrency.parseFromISO87String(additionalAmount, currencyTransaction)
							.setScale(2, ROUNDING_MODE));
				}

				// emvData DE55
				// Value from scheme or sent to scheme or received from POS.
				//				if (response != null && response.hasField(55)) {
				//					tx.setEmvData(response.getString(55));
				//				} else if (req2Scheme.hasField(55)) {
				//					tx.setEmvData(req2Scheme.getString(55));
				//				} else if (reqFromPOS.hasField(55)) {
				//					tx.setEmvData(reqFromPOS.getString(55));
				//				}

				//removed as per RBI mandates
				//if (req2Scheme.hasField(55)) {
				//	tx.setEmvData(req2Scheme.getString(55));
				//}
				
				// Batch Number
				if (reqFromPOS.hasField(56)) {
					tx.setBatchNumber(reqFromPOS.getString(56));
				}

				// POS Data code
				if (req2Scheme.hasField(61)) {
					tx.setAdditionalPOSData(req2Scheme.getString(61));
				}
				// Invoice Number
				if (reqFromPOS.hasField(62)) {
					tx.setInvoiceNumber(reqFromPOS.getString(62));
				}

				tx.setAtomMID(Long.parseLong(terminaldata.getString("atomMID")));
				tx.setTerminalType(terminaldata.getString("terminalType"));

				tx.setIsOnusTransaction((int) ctx.get(EFSCTXConstants.IS_ONUS_TRANSACTION));
				//tx.setSMSDMSIndicator(issuer.getMessageType());


				tx.setSMSDMSIndicator(issuerdata.getString("messageType"));
				
				//change by mayur pawar on 1st june 2022.
				tx.setDomesticInternational(issuerdata.getString("domesticInternational"));

				String encryptedPAN = ctx.getString(EFSCTXConstants.ENCRYPTED_PAN);

			if (encryptedPAN == null) {
					encryptedPAN = EFSCrypto.encrypt3DES(card.getPan());
				}
			/*	
=======
				//removed as per RBI mandates
				//String encryptedPAN = ctx.getString(EFSCTXConstants.ENCRYPTED_PAN);
				//if (encryptedPAN == null) {
				//	encryptedPAN = EFSCrypto.encrypt3DES(card.getPan());
				//}
>>>>>>> origin/uat*/
				//Storing card data in OTS
				//String token = new GenerateCardToken().generatePANCardToken(ctx);
				//tx.setCardToken(token);				//added @10012022 for token in db
				//tx.setEncryptedPAN(encryptedPAN);
				//change to handel null pointer in hitachi failed response case by PV @07122021
				//if (response != null && response.hasField(55)) {
				//	tx.setEMVresponse(response.getString(55));
				//}
				
				
				tx.setMessageStatus((int) ctx.get(EFSCTXConstants.MESSAGE_PROCESSING_STATUS));
				tx.setNode(EFSGlobalData.getProperty("Node"));

				// New fields added for Middleware
				if (reqFromPOS.hasField(57)) {// Mobile
					tx.setMobile(reqFromPOS.getString(47));
				}
				if (reqFromPOS.hasField(58)) {// PID
					tx.setPid(reqFromPOS.getString(48));
				}
				if (reqFromPOS.hasField(59)) {// URNs
					tx.setUrn(reqFromPOS.getString(49));
				}

				EMITransactionData emi = (EMITransactionData) ctx.get(EFSCTXConstants.EMI_TRANSACTION_DATA);

				if (emi != null) {// EMI fields
					tx.setEmiBankName(emi.getBankName());// Bank Name
					tx.setEmiTenure(emi.getTenure());// Tenure
					tx.setEmiROI(emi.getRoi());// ROI
					tx.setEmiCustomerName(emi.getCustomerName());// Customer Name
					tx.setEmiCustomerEmailID(emi.getCustomerEmail());// CustomerEmail
				}

				// The following required else you get following error in RDS mySQL
				// SQLSTATE[23000]: Integrity constraint violation: 1048 Column 'column_name'
				// cannot be null' error when not specifying a value.
				tx.setServerTimestamp(new Date());

				// Added to handle DE102 requirement of BOSS (Rupay) for Void and Reversals
				if (response != null && response.hasField(102)) {
					tx.setDe102(response.getString(102));
				}

				// added on 27th 0ct 2021
				tx.setSwitchName("HITACHI");
				tx.setMerchantName(merchantdata.getString("merchName"));
				if (!(terminaldata.getString("terminalAddr1") == null)) {
					tx.setLocation(terminaldata.getString("terminalAddr1"));
				}

				

				// added by anuj on 6th sept 2021
				String atomTxnId = ctx.get(EFSCTXConstants.ATOM_TXN_ID);

				log.info("MW_"+tracerId+" atomTxnId in LOG : "+atomTxnId);

				tx.setAtomTxnId(atomTxnId);
				ctx.put(EFSCTXConstants.TITAN_TRANSACTION_DATA, tx);
				ctx.put(EFSCTXConstants.TITAN_TRANSACTION_COMPOSITEKEY, tc);

				// added on 27th 0ct 2021
				ctx.put(EFSCTXConstants.SWITCHNAME, "HITACHI");
				
				String switchName="HITACHI";
				//String rrn=response.getString(37);
				
				//Transaction data = transactionDaoImpl.fetchDataFromTxnFromRRN(switchName, response.getString(37), tracerId);
				
//				if(response!=null && Objects.equals(response.getString(37), data.getRrn())) {
//					
//				log.info("data is already in database:"+data);
//				}
//				else {
//				db.save(tx);
//				db.commit();// Keshaw
//				}
				
				
					db.save(tx);
					db.commit();// Keshaw
					
				
				
			}

			/*
			 * if(db != null) { //db.session().clear(); db.saveOrUpdate(tx); }
			 */
		} catch (Exception e) {
			db.rollback();
			// Keshaw
			e.printStackTrace();
		} finally {
			db.close();
		}
	}

	private BigDecimal parseAmountConversionRate(String de9, int scale) {
		if (de9 == null || de9.length() != 8)
			throw new IllegalArgumentException("Invalid amount converion rate argument: '" + de9 + "'");
		BigDecimal bd = new BigDecimal(de9);
		int pow = bd.movePointLeft(7).intValue();
		bd = new BigDecimal(de9.substring(1));
		return bd.movePointLeft(pow).setScale(scale, ROUNDING_MODE);
	}

	@Override
	public void setConfiguration(Configuration cfg) throws ConfigurationException {
		scheme = cfg.get("scheme", "rupay");
	}
}