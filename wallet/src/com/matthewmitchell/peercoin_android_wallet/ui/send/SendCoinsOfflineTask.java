/*
 * Copyright 2013-2014 the original author or authors.
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

package com.matthewmitchell.peercoin_android_wallet.ui.send;

import javax.annotation.Nonnull;

import android.os.Handler;
import android.os.Looper;

import com.fuelcoinj.core.Coin;
import com.fuelcoinj.core.InsufficientMoneyException;
import com.fuelcoinj.core.Transaction;
import com.fuelcoinj.core.Wallet;
import com.fuelcoinj.crypto.KeyCrypterException;

/**
 * @author Andreas Schildbach
 */
public abstract class SendCoinsOfflineTask
{
	private final Wallet wallet;
	private final Handler backgroundHandler;
	private final Handler callbackHandler;

	public SendCoinsOfflineTask(@Nonnull final Wallet wallet, @Nonnull final Handler backgroundHandler)
	{
		this.wallet = wallet;
		this.backgroundHandler = backgroundHandler;
		this.callbackHandler = new Handler(Looper.myLooper());
	}

	public final void sendCoinsOffline(@Nonnull final Wallet.SendRequest sendRequest)
	{
		backgroundHandler.post(new Runnable()
		{
			@Override
			public void run()
			{
				try
				{
					final Transaction transaction = wallet.sendCoinsOffline(sendRequest); // can take long

					callbackHandler.post(new Runnable()
					{
						@Override
						public void run()
						{
							onSuccess(transaction);
						}
					});
				}
				catch (final InsufficientMoneyException x)
				{
					callbackHandler.post(new Runnable()
					{
						@Override
						public void run()
						{
							onInsufficientMoney(x.missing);
						}
					});
				}
				catch (final KeyCrypterException x)
				{
					callbackHandler.post(new Runnable()
					{
						@Override
						public void run()
						{
							onInvalidKey();
						}
					});
				}
				catch (final Wallet.CouldNotAdjustDownwards x)
				{
					callbackHandler.post(new Runnable()
					{
						@Override
						public void run()
						{
							onEmptyWalletFailed();
						}
					});
				}
				catch (final Wallet.CompletionException x)
				{
					callbackHandler.post(new Runnable()
					{
						@Override
						public void run()
						{
							onFailure(x);
						}
					});
				}
			}
		});
	}

	protected abstract void onSuccess(@Nonnull Transaction transaction);

	protected abstract void onInsufficientMoney(@Nonnull Coin missing);

	protected abstract void onInvalidKey();

	protected void onEmptyWalletFailed()
	{
		onFailure(new Wallet.CouldNotAdjustDownwards());
	}

	protected abstract void onFailure(@Nonnull Exception exception);
}
