/*
 * Copyright 2011-2014 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.capricoin.capricoin_android_wallet;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Currency;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.provider.BaseColumns;
import android.text.format.DateUtils;

import com.capricoinj.core.Coin;
import com.capricoinj.utils.ExchangeRate;
import com.capricoinj.utils.Fiat;
import com.google.common.base.Charsets;

import com.capricoin.capricoin_android_wallet.util.GenericUtils;
import com.capricoin.capricoin_android_wallet.util.Io;

/**
 * @author Andreas Schildbach
 */
public class ExchangeRatesProvider extends ContentProvider
{
	public static class WalletExchangeRate
	{
		public WalletExchangeRate(@Nonnull final ExchangeRate rate, final String source)
		{
			this.rate = rate;
			this.source = source;
		}

		public final ExchangeRate rate;
		public final String source;

		public String getCurrencyCode()
		{
			return rate.fiat.currencyCode;
		}

		@Override
		public String toString()
		{
			return getClass().getSimpleName() + '[' + rate.fiat + ']';
		}
	}

	public static final String KEY_CURRENCY_CODE = "currency_code";
	private static final String KEY_RATE_COIN = "rate_coin";
	private static final String KEY_RATE_FIAT = "rate_fiat";
	private static final String KEY_SOURCE = "source";

	public static final String QUERY_PARAM_Q = "q";
	private static final String QUERY_PARAM_OFFLINE = "offline";

	private Configuration config;
	private String userAgent;

	@CheckForNull
	private Map<String, WalletExchangeRate> exchangeRates = null;
	private long lastUpdated = 0;

	private static final String[] BITCOINAVERAGE_FIELDS = new String[] { "24h_avg", "last" };
	private static final URL BITCOINAVERAGE_URL;
	private static final String BTCE_SOURCE = "btc-e.com";

	// https://bitmarket.eu/api/ticker

	static {
		try {
			//BTCE_URL = new URL("https://btc-e.com/api/2/ppc_usd/ticker");
			BITCOINAVERAGE_URL = new URL("https://api.bitcoinaverage.com/ticker/global/all");
		} catch (final MalformedURLException x) {
			throw new RuntimeException(x); // cannot happen
		}
	}

	private static final long UPDATE_FREQ_MS = 10 * DateUtils.MINUTE_IN_MILLIS;


	private static final Logger log = LoggerFactory.getLogger(ExchangeRatesProvider.class);

	@Override
	public boolean onCreate()
	{
		final Context context = getContext();

		this.config = new Configuration(PreferenceManager.getDefaultSharedPreferences(context));

		this.userAgent = WalletApplication.httpUserAgent(WalletApplication.packageInfoFromContext(context).versionName);

		final WalletExchangeRate cachedExchangeRate = config.getCachedExchangeRate();
		if (cachedExchangeRate != null)
		{
			exchangeRates = new TreeMap<String, WalletExchangeRate>();
			exchangeRates.put(cachedExchangeRate.getCurrencyCode(), cachedExchangeRate);
		}

		return true;
	}

	public static Uri contentUri(@Nonnull final String packageName, final boolean offline)
	{
		final Uri.Builder uri = Uri.parse("content://" + packageName + '.' + "exchange_rates").buildUpon();
		if (offline)
			uri.appendQueryParameter(QUERY_PARAM_OFFLINE, "1");
		return uri.build();
	}

	@Override
	public Cursor query(final Uri uri, final String[] projection, final String selection, final String[] selectionArgs, final String sortOrder)
	{
		final long now = System.currentTimeMillis();

		final boolean offline = uri.getQueryParameter(QUERY_PARAM_OFFLINE) != null;

		if (!offline && (lastUpdated == 0 || now - lastUpdated > UPDATE_FREQ_MS))
		{
			Map<String, WalletExchangeRate> newExchangeRates = null;
			if (newExchangeRates == null)
				newExchangeRates = requestExchangeRates(userAgent);

			if (newExchangeRates != null)
			{
				exchangeRates = newExchangeRates;
				lastUpdated = now;

				final WalletExchangeRate exchangeRateToCache = bestExchangeRate(config.getExchangeCurrencyCode());
				if (exchangeRateToCache != null)
					config.setCachedExchangeRate(exchangeRateToCache);
			}
		}

		if (exchangeRates == null)
			return null;

		final MatrixCursor cursor = new MatrixCursor(new String[] { BaseColumns._ID, KEY_CURRENCY_CODE, KEY_RATE_COIN, KEY_RATE_FIAT, KEY_SOURCE });

		if (selection == null)
		{
			for (final Map.Entry<String, WalletExchangeRate> entry : exchangeRates.entrySet())
			{
				final WalletExchangeRate exchangeRate = entry.getValue();
				final ExchangeRate rate = exchangeRate.rate;
				final String currencyCode = exchangeRate.getCurrencyCode();
				cursor.newRow().add(currencyCode.hashCode()).add(currencyCode).add(rate.coin.value).add(rate.fiat.value).add(exchangeRate.source);
			}
		}
		else if (selection.equals(QUERY_PARAM_Q))
		{
			final String selectionArg = selectionArgs[0].toLowerCase(Locale.US);
			for (final Map.Entry<String, WalletExchangeRate> entry : exchangeRates.entrySet())
			{
				final WalletExchangeRate exchangeRate = entry.getValue();
				final ExchangeRate rate = exchangeRate.rate;
				final String currencyCode = exchangeRate.getCurrencyCode();
				final String currencySymbol = GenericUtils.currencySymbol(currencyCode);
				if (currencyCode.toLowerCase(Locale.US).contains(selectionArg) || currencySymbol.toLowerCase(Locale.US).contains(selectionArg))
					cursor.newRow().add(currencyCode.hashCode()).add(currencyCode).add(rate.coin.value).add(rate.fiat.value).add(exchangeRate.source);
			}
		}
		else if (selection.equals(KEY_CURRENCY_CODE))
		{
			final String selectionArg = selectionArgs[0];
			final WalletExchangeRate exchangeRate = bestExchangeRate(selectionArg);
			if (exchangeRate != null)
			{
				final ExchangeRate rate = exchangeRate.rate;
				final String currencyCode = exchangeRate.getCurrencyCode();
				cursor.newRow().add(currencyCode.hashCode()).add(currencyCode).add(rate.coin.value).add(rate.fiat.value).add(exchangeRate.source);
			}
		}

		return cursor;
	}

	private WalletExchangeRate bestExchangeRate(final String currencyCode)
	{
		WalletExchangeRate rate = currencyCode != null ? exchangeRates.get(currencyCode) : null;
		if (rate != null)
			return rate;

		final String defaultCode = defaultCurrencyCode();
		rate = defaultCode != null ? exchangeRates.get(defaultCode) : null;

		if (rate != null)
			return rate;

		return exchangeRates.get(Constants.DEFAULT_EXCHANGE_CURRENCY);
	}

	private String defaultCurrencyCode()
	{
		try
		{
			return Currency.getInstance(Locale.getDefault()).getCurrencyCode();
		}
		catch (final IllegalArgumentException x)
		{
			return null;
		}
	}

	public static WalletExchangeRate getExchangeRate(@Nonnull final Cursor cursor)
	{
		final String currencyCode = cursor.getString(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_CURRENCY_CODE));
		final Coin rateCoin = Coin.valueOf(cursor.getLong(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_RATE_COIN)));
		final Fiat rateFiat = Fiat.valueOf(currencyCode, cursor.getLong(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_RATE_FIAT)));
		final String source = cursor.getString(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_SOURCE));

		return new WalletExchangeRate(new ExchangeRate(rateCoin, rateFiat), source);
	}

	@Override
	public Uri insert(final Uri uri, final ContentValues values)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public int update(final Uri uri, final ContentValues values, final String selection, final String[] selectionArgs)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public int delete(final Uri uri, final String selection, final String[] selectionArgs)
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public String getType(final Uri uri)
	{
		throw new UnsupportedOperationException();
	}

	private static String getURLResult(URL url, final String userAgent) {

		HttpURLConnection connection = null;
		Reader reader = null;

		try {
			connection = (HttpURLConnection) url.openConnection();

			connection.setInstanceFollowRedirects(false);
			connection.setConnectTimeout(Constants.HTTP_TIMEOUT_MS);
			connection.setReadTimeout(Constants.HTTP_TIMEOUT_MS);
			connection.addRequestProperty("User-Agent", userAgent);
			connection.addRequestProperty("Accept-Encoding", "gzip");
			connection.connect();

			final int responseCode = connection.getResponseCode();
			if (responseCode == HttpURLConnection.HTTP_OK) {

				final String contentEncoding = connection.getContentEncoding();

				InputStream is = new BufferedInputStream(connection.getInputStream(), 1024);
				if ("gzip".equalsIgnoreCase(contentEncoding))
					is = new GZIPInputStream(is);

				reader = new InputStreamReader(is, Charsets.UTF_8);
				final StringBuilder content = new StringBuilder();

				Io.copy(reader, content);
				return content.toString();

			} else {
				log.warn("http status {} when fetching exchange rates from {}", responseCode, url);
			}
		} catch (final Exception x) {
			log.warn("problem fetching exchange rates from " + url, x);
		} finally {
			if (reader != null) {
				try {
					reader.close();
				} catch (final IOException x) {
					// swallow
				}
			}

			if (connection != null)
				connection.disconnect();
		}

		return null;

	}

	private static Map<String, WalletExchangeRate> requestExchangeRates(final String userAgent) {


		final long start = System.currentTimeMillis();

		HttpURLConnection connection = null;
		Reader reader = null;

		try
		{

			Double btcRate = 0.0;

			Object result = getCoinValueBTC_BTER();

				if(result == null)
					return null;

			btcRate = (Double)result;


			connection = (HttpURLConnection) BITCOINAVERAGE_URL.openConnection();

			connection.setInstanceFollowRedirects(false);
			connection.setConnectTimeout(Constants.HTTP_TIMEOUT_MS);
			connection.setReadTimeout(Constants.HTTP_TIMEOUT_MS);
			connection.addRequestProperty("User-Agent", userAgent);
			connection.connect();

			final int responseCode = connection.getResponseCode();
			if (responseCode == HttpURLConnection.HTTP_OK)
			{
				reader = new InputStreamReader(new BufferedInputStream(connection.getInputStream(), 1024), Charsets.UTF_8);
				final StringBuilder content = new StringBuilder();
				Io.copy(reader, content);

				final Map<String, WalletExchangeRate> rates = new TreeMap<String, WalletExchangeRate>();

				final JSONObject head = new JSONObject(content.toString());
				for (final Iterator<String> i = head.keys(); i.hasNext();)
				{
					final String currencyCode = i.next();
					if (!"timestamp".equals(currencyCode))
					{
						final JSONObject o = head.getJSONObject(currencyCode);

						for (final String field : BITCOINAVERAGE_FIELDS)
						{
							String rateStr = o.optString(field, null);

							if (rateStr != null)
							{
								try
								{
									//double rateForBTC = Double.parseDouble(rateStr);

									BigDecimal rateForBTC = new BigDecimal(rateStr).divide(new BigDecimal(10000), RoundingMode.HALF_UP);

									rateStr = String.format("%.8f", rateForBTC.doubleValue() * btcRate).replace(",", ".");

									//TODO make sre this is correct....
									Coin coin = Coin.parseCoin(rateStr);
									final BigInteger rate = BigInteger.valueOf(coin.getValue());

									if (rate.signum() > 0)
									{
										Fiat price = Fiat.valueOf(currencyCode, coin.getValue());
										ExchangeRate exRate = new ExchangeRate(price);
										rates.put(currencyCode, new WalletExchangeRate(exRate, BITCOINAVERAGE_URL.getHost()));
										break;
									}
								}
								catch (final ArithmeticException x)
								{
									log.warn("problem fetching {} exchange rate from {}: {}", new Object[] { currencyCode, BITCOINAVERAGE_URL, x.getMessage() });
								}

							}
						}
					}
				}

				log.info("fetched exchange rates from {}, took {} ms", BITCOINAVERAGE_URL, (System.currentTimeMillis() - start));

				//Add Bitcoin information
				if(rates.size() == 0)
				{
					int i = 0;
					i++;
				}
				else
				{
					Coin cpccoin = Coin.parseCoin(btcRate.toString());
					Fiat cpcprice = Fiat.valueOf("BTC", cpccoin.getValue());
					ExchangeRate cpcexchangerate = new ExchangeRate( cpcprice);
					rates.put("BTC",new WalletExchangeRate(cpcexchangerate , "https://bittrex.com"));
					//rates.put("mFC2" , new ExchangeRate("mFC2", GenericUtils.toNanoCoins(String.format("%.5f", btcRate*1000).replace(",", "."), 0), cryptsyValue ? "pubapi.cryptsy.com" : "data.bter.com"));
				}


				return rates;
			}
			else
			{
				log.warn("http status {} when fetching {}", responseCode, BITCOINAVERAGE_URL);
			}
		}
		catch (final Exception x)
		{
			log.warn("problem fetching exchange rates from " + BITCOINAVERAGE_URL, x);
		}
		finally
		{

			if (reader != null)
			{
				try
				{
					reader.close();
				}
				catch (final IOException x)
				{
					// swallow
				}
			}

			if (connection != null)
				connection.disconnect();
		}

		return null;






		/*final Map<String, WalletExchangeRate> rates = new TreeMap<String, WalletExchangeRate>();

		String usdPriceResult = getURLResult(BTCE_URL, userAgent);
		if (usdPriceResult == null)
			return null;

		final Fiat usdPrice;

		try {
			JSONObject jsonResult = new JSONObject(usdPriceResult);
			String usdStr = jsonResult.getJSONObject("ticker").getString("last");
			usdPrice = Fiat.parseFiat("USD", usdStr);
		}catch (JSONException e) {
			return null;
		}

		rates.put("USD", new WalletExchangeRate(new ExchangeRate(usdPrice), BTCE_SOURCE));

		// Now for USD conversions

		URL yahoo;

		try {
			yahoo = new URL(
					Uri.parse("https://query.yahooapis.com/v1/public/yql")
							.buildUpon()
							.appendQueryParameter("q", "select * from yahoo.finance.xchange where pair=\"usdeur, usdgbp, usdcny, usdjpy, usdsgd, usdhkd, usdcad, usdnzd, usdaud, usdclp, usddkk, usdsek, usdisk, usdchf, usdbrl, usdrub, usdpln, usdthb, usdkrw, usdtwd\"")
							.appendQueryParameter("format", "json")
							.appendQueryParameter("env", "store://datatables.org/alltableswithkeys")
							.build().toString()
			);
		} catch (final MalformedURLException e) {
			return rates;
		}

		String yahooRes = getURLResult(yahoo, userAgent);
		if (yahooRes != null) {
			try {

				JSONObject head = new JSONObject(yahooRes);
				JSONArray convs = head.getJSONObject("query").getJSONObject("results").getJSONArray("rate");

				for (int x = 0; x < convs.length(); x++) {
					JSONObject conv = convs.getJSONObject(x);
					String currency = conv.getString("id").substring(3);
					String priceString = conv.getString("Rate");

					// Divide by 10000 as Fiat are moved 4 decimal places
					Fiat price = Fiat.parseFiat(currency, priceString).multiply(usdPrice.longValue()).divide(10000);

					rates.put(currency, new WalletExchangeRate(new ExchangeRate(price), BTCE_SOURCE));
				}

			} catch (Exception e) {
				log.warn("Got error reading yahoo exchange rates: {}", e);
			}
		}

		return rates;*/

	}



	private static Object getCoinValueBTC_BTER()
	{
		Date date = new Date();
		long now = date.getTime();



		//final Map<String, ExchangeRate> rates = new TreeMap<String, ExchangeRate>();
		// Keep the LTC rate around for a bit
		Double btcRate = 0.0;
		String currency = "BTC";
		String url = "https://bittrex.com/api/v1.1/public/getticker?market=BTC-CPC";




		HttpURLConnection connection = null;
		try {
			// final String currencyCode = currencies[i];
			final URL URL_bter = new URL(url);
			connection = (HttpURLConnection)URL_bter.openConnection();
			connection.setConnectTimeout(Constants.HTTP_TIMEOUT_MS * 2);
			connection.setReadTimeout(Constants.HTTP_TIMEOUT_MS * 2);
			connection.connect();

			final StringBuilder content = new StringBuilder();

			Reader reader = null;
			try
			{
				reader = new InputStreamReader(new BufferedInputStream(connection.getInputStream(), 1024));
				Io.copy(reader, content);
				final JSONObject head = new JSONObject(content.toString());
				String success = head.getString("success");
				if(success.equals("true"))
				{
					JSONObject result = head.getJSONObject("result");
					Double averageTrade = result.getDouble("Bid");

					btcRate = averageTrade;
				}

			}
			finally
			{
				if (reader != null)
				{
					try
					{
						reader.close();
					}
					catch (final IOException x)
					{
						// swallow
					}
				}
			}
			return btcRate;
		}
		catch (final IOException x)
		{
			log.warn("problem fetching exchange rates from " + url, x);

		}
		catch (final JSONException x) {
			log.warn("problem parsing json exchange rates from " + url, x);
		}
		finally {
			if (connection != null)
				connection.disconnect();
		}

		return null;
	}

}

