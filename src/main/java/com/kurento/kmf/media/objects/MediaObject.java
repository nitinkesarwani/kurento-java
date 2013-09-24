package com.kurento.kmf.media.objects;

import static com.kurento.kmf.media.internal.refs.MediaRefConverter.fromThrift;

import java.nio.ByteBuffer;

import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import com.kurento.kmf.common.exception.KurentoMediaFrameworkException;
import com.kurento.kmf.media.Continuation;
import com.kurento.kmf.media.commands.MediaCommand;
import com.kurento.kmf.media.commands.MediaCommandResult;
import com.kurento.kmf.media.events.KmsEvent;
import com.kurento.kmf.media.internal.MediaApiConfiguration;
import com.kurento.kmf.media.internal.MediaEventListener;
import com.kurento.kmf.media.internal.MediaServerCallbackHandler;
import com.kurento.kmf.media.internal.refs.MediaObjectRefDTO;
import com.kurento.kmf.media.internal.refs.MediaPipelineRefDTO;
import com.kurento.kmf.media.pool.MediaServerClientPoolService;
import com.kurento.kms.thrift.api.Command;
import com.kurento.kms.thrift.api.CommandResult;
import com.kurento.kms.thrift.api.MediaServerException;
import com.kurento.kms.thrift.api.MediaServerService.AsyncClient;
import com.kurento.kms.thrift.api.MediaServerService.AsyncClient.getMediaPipeline_call;
import com.kurento.kms.thrift.api.MediaServerService.AsyncClient.getParent_call;
import com.kurento.kms.thrift.api.MediaServerService.AsyncClient.release_call;
import com.kurento.kms.thrift.api.MediaServerService.AsyncClient.sendCommand_call;
import com.kurento.kms.thrift.api.MediaServerService.AsyncClient.subscribe_call;
import com.kurento.kms.thrift.api.MediaServerService.AsyncClient.unsubscribe_call;
import com.kurento.kms.thrift.api.MediaServerService.Client;

public abstract class MediaObject {

	protected final MediaObjectRefDTO objectRef;

	@Autowired
	protected MediaServerClientPoolService clientPool;

	@Autowired
	private MediaServerCallbackHandler handler;

	@Autowired
	protected ApplicationContext ctx;

	@Autowired
	private MediaApiConfiguration config;

	private MediaPipeline pipeline;

	private MediaObject parent;

	// TODO: this should not be visible to final developers
	public MediaObjectRefDTO getObjectRef() {
		return objectRef;
	}

	public MediaObject(MediaObjectRefDTO ref) {
		objectRef = ref;
	}

	/**
	 * Explicitly release a media object form memory. All of its children will
	 * also be released
	 * 
	 * @throws MediaServerException
	 * @throws InvokationException
	 */
	public void release() throws KurentoMediaFrameworkException {
		Client client = clientPool.acquireSync();

		try {
			client.release(this.objectRef.getThriftRef());
		} catch (MediaServerException e) {
			throw new KurentoMediaFrameworkException(e.getMessage(), e,
					e.getErrorCode());
		} catch (TException e) {
			// TODO change error code
			throw new KurentoMediaFrameworkException(e.getMessage(), e, 30000);
		} finally {
			clientPool.release(client);
		}

		// TODO remove from DGC
	}

	/**
	 * This methods subscribes to events generated by this media object.
	 * 
	 * @param handlerAddress
	 * @param handlerPort
	 * @throws MediaServerException
	 * @throws InvokationException
	 */
	public <E extends KmsEvent> String addListener(
			MediaEventListener<E> listener)
			throws KurentoMediaFrameworkException {
		Client client = clientPool.acquireSync();

		String callbackToken;

		try {
			callbackToken = client.subscribe(objectRef.getThriftRef(),
					config.getHandlerAddress(), config.getHandlerPort());
		} catch (MediaServerException e) {
			throw new KurentoMediaFrameworkException(e.getMessage(), e,
					e.getErrorCode());
		} catch (TException e) {
			// TODO change error code
			throw new KurentoMediaFrameworkException(e.getMessage(), e, 30000);
		} finally {
			clientPool.release(client);
		}

		listener.setCallbackToken(callbackToken);
		handler.addListener(this, listener);

		return callbackToken;
	}

	public <E extends KmsEvent> void removeListener(
			MediaEventListener<E> listener)
			throws KurentoMediaFrameworkException {
		Client client = clientPool.acquireSync();

		try {
			client.unsubscribe(objectRef.getThriftRef(),
					listener.getCallbackToken());
		} catch (MediaServerException e) {
			throw new KurentoMediaFrameworkException(e.getMessage(), e,
					e.getErrorCode());
		} catch (TException e) {
			// TODO change error code
			throw new KurentoMediaFrameworkException(e.getMessage(), e, 30000);
		} finally {
			clientPool.release(client);
		}
		handler.removeListener(this, listener);
	}

	/**
	 * Sends a command to a media object. Classes that extend
	 * {@link MediaObject} should invoke command in the server throguh this
	 * method, wrapping the command in specific methods in the inheriting class.
	 * 
	 * @param command
	 * @return
	 * @throws MediaServerException
	 * @throws InvokationException
	 */
	protected MediaCommandResult sendCommand(MediaCommand command)
			throws KurentoMediaFrameworkException {
		Client client = clientPool.acquireSync();

		CommandResult result;

		try {
			result = client.sendCommand(objectRef.getThriftRef(), new Command(
					command.getType(), ByteBuffer.wrap(command.getData())));
		} catch (MediaServerException e) {
			throw new KurentoMediaFrameworkException(e.getMessage(), e,
					e.getErrorCode());
		} catch (TException e) {
			// TODO change error code
			throw new KurentoMediaFrameworkException(e.getMessage(), e, 30000);
		} finally {
			clientPool.release(client);
		}

		return (MediaCommandResult) ctx.getBean("mediaCommandResult",
				command.getType(), result); // TODO: implement basing on
											// annotations and call
											// mediaCommandResult.deserializeCommandResult(result);
	}

	/**
	 * This method is invoked periodically to avoid garbage collection
	 * 
	 * @throws MediaServerException
	 * @throws InvokationException
	 */
	protected void keepAlive() throws KurentoMediaFrameworkException {
		// TODO remove from here
		Client client = clientPool.acquireSync();

		try {
			client.keepAlive(objectRef.getThriftRef());
		} catch (MediaServerException e) {
			throw new KurentoMediaFrameworkException(e.getMessage(), e,
					e.getErrorCode());
		} catch (TException e) {
			// TODO change error code
			throw new KurentoMediaFrameworkException(e.getMessage(), e, 30000);
		} finally {
			clientPool.release(client);
		}
	}

	@Override
	protected void finalize() {
		// TODO remove from the DGC container that holds
		// the referenced objects, so the keepalive method is not
		// called any more
	}

	/**
	 * Returns the parent of this media object. The type of the parent depends
	 * on the type of the element that this method is called upon. <li>
	 * MediaPad->MediaElement</li> <li>MediaMixer->MediaPipeline</li> <li>
	 * MediaElement->MediaPipeline</li> <li>MediaPipeline->null</li>
	 * 
	 * @return The parent
	 * @throws MediaServerException
	 * @throws InvokationException
	 */
	public MediaObject getParent() throws KurentoMediaFrameworkException {

		if (parent == null) {
			Client client = clientPool.acquireSync();

			MediaObjectRefDTO objRefDTO;

			try {
				objRefDTO = fromThrift(client.getParent(objectRef
						.getThriftRef()));
			} catch (MediaServerException e) {
				throw new KurentoMediaFrameworkException(e.getMessage(), e,
						e.getErrorCode());
			} catch (TException e) {
				// TODO change error code
				throw new KurentoMediaFrameworkException(e.getMessage(), e,
						30000);
			} finally {
				clientPool.release(client);
			}

			parent = (MediaObject) ctx.getBean("mediaObject", objRefDTO);
		}

		return parent;
	}

	/**
	 * Returns the pipeline to which this MediaObject belong, or the pipeline
	 * itself if invoked over a {@link MediaPipeline}
	 * 
	 * @return The media pipeline for the object, or <code>this</code> in case
	 *         of a media pipeline
	 * @throws MediaServerException
	 * @throws InvokationException
	 */
	public MediaPipeline getMediaPipeline()
			throws KurentoMediaFrameworkException {

		if (pipeline == null) {
			Client client = clientPool.acquireSync();

			MediaPipelineRefDTO pipelineRefDTO;
			try {
				pipelineRefDTO = new MediaPipelineRefDTO(
						client.getMediaPipeline(objectRef.getThriftRef()));
			} catch (MediaServerException e) {
				throw new KurentoMediaFrameworkException(e.getMessage(), e,
						e.getErrorCode());
			} catch (TException e) {
				// TODO change error code
				throw new KurentoMediaFrameworkException(e.getMessage(), e,
						30000);
			} finally {
				clientPool.release(client);
			}

			pipeline = (MediaPipeline) ctx.getBean("mediaPipeline",
					pipelineRefDTO);
		}

		return pipeline;
	}

	/**
	 * Explicitly release a media object form memory. All of its children will
	 * also be released
	 * 
	 * @throws MediaServerException
	 * @throws InvokationException
	 */
	public void release(final Continuation<Void> cont)
			throws KurentoMediaFrameworkException {
		final AsyncClient client = clientPool.acquireAsync();

		try {
			client.release(objectRef.getThriftRef(),
					new AsyncMethodCallback<AsyncClient.release_call>() {

						@Override
						public void onError(Exception exception) {
							clientPool.release(client);
							cont.onError(exception);
						}

						@Override
						public void onComplete(release_call response) {
							try {
								response.getResult();
							} catch (MediaServerException e) {
								throw new KurentoMediaFrameworkException(e
										.getMessage(), e, e.getErrorCode());
							} catch (TException e) {
								// TODO change error code
								throw new KurentoMediaFrameworkException(e
										.getMessage(), e, 30000);
							} finally {
								clientPool.release(client);
							}
							cont.onSuccess(null);
						}
					});
		} catch (TException e) {
			clientPool.release(client);
			// TODO change error code
			throw new KurentoMediaFrameworkException(e.getMessage(), e, 30000);
		}

	}

	/**
	 * This methods subscribes to events generated by this media object.
	 * 
	 * @param handlerAddress
	 * @param handlerPort
	 * @throws MediaServerException
	 * @throws InvokationException
	 */
	public <E extends KmsEvent> void addListener(
			final MediaEventListener<E> listener,
			final Continuation<String> cont)
			throws KurentoMediaFrameworkException {

		final AsyncClient client = clientPool.acquireAsync();

		try {

			client.subscribe(this.objectRef.getThriftRef(),
					config.getHandlerAddress(), config.getHandlerPort(),
					new AsyncMethodCallback<subscribe_call>() {

						@Override
						public void onError(Exception exception) {
							clientPool.release(client);
							cont.onError(exception);
						}

						@Override
						public void onComplete(subscribe_call response) {
							String token;
							try {
								token = response.getResult();
							} catch (MediaServerException e) {
								throw new KurentoMediaFrameworkException(e
										.getMessage(), e, e.getErrorCode());
							} catch (TException e) {
								// TODO change error code
								throw new KurentoMediaFrameworkException(e
										.getMessage(), e, 30000);
							} finally {
								clientPool.release(client);
							}
							listener.setCallbackToken(token);
							handler.addListener(MediaObject.this, listener);
							cont.onSuccess(token);
						}
					});
		} catch (TException e) {
			clientPool.release(client);
			// TODO change error code
			throw new KurentoMediaFrameworkException(e.getMessage(), e, 30000);
		}
	}

	public <E extends KmsEvent> void removeListener(
			final MediaEventListener<E> listener, final Continuation<Void> cont)
			throws KurentoMediaFrameworkException {

		final AsyncClient client = clientPool.acquireAsync();

		try {
			client.unsubscribe(objectRef.getThriftRef(),
					listener.getCallbackToken(),
					new AsyncMethodCallback<unsubscribe_call>() {

						@Override
						public void onError(Exception exception) {
							clientPool.release(client);
							cont.onError(exception);
						}

						@Override
						public void onComplete(unsubscribe_call response) {
							try {
								response.getResult();
							} catch (MediaServerException e) {
								throw new KurentoMediaFrameworkException(e
										.getMessage(), e, e.getErrorCode());
							} catch (TException e) {
								// TODO change error code
								throw new KurentoMediaFrameworkException(e
										.getMessage(), e, 30000);
							} finally {
								clientPool.release(client);
							}

							handler.removeListener(MediaObject.this, listener);
							cont.onSuccess(null);
						}
					});
		} catch (TException e) {
			clientPool.release(client);
			// TODO change error code
			throw new KurentoMediaFrameworkException(e.getMessage(), e, 30000);
		}

	}

	/**
	 * Sends a command to a media object. Classes that extend
	 * {@link MediaObject} should invoke command in the server throguh this
	 * method, wrapping the command in specific methods in the inheriting class.
	 * 
	 * @param command
	 * @return
	 * @throws MediaServerException
	 * @throws InvokationException
	 */
	protected void sendCommand(Command command,
			final Continuation<CommandResult> cont)
			throws KurentoMediaFrameworkException {
		final AsyncClient client = this.clientPool.acquireAsync();

		try {
			client.sendCommand(this.objectRef.getThriftRef(), command,
					new AsyncMethodCallback<sendCommand_call>() {

						@Override
						public void onError(Exception exception) {
							clientPool.release(client);
							cont.onError(exception);
						}

						@Override
						public void onComplete(sendCommand_call response) {
							CommandResult result;

							try {
								result = response.getResult();
							} catch (MediaServerException e) {
								throw new KurentoMediaFrameworkException(e
										.getMessage(), e, e.getErrorCode());
							} catch (TException e) {
								// TODO change error code
								throw new KurentoMediaFrameworkException(e
										.getMessage(), e, 30000);
							} finally {
								clientPool.release(client);
							}

							cont.onSuccess(result);
						}
					});
		} catch (TException e) {
			clientPool.release(client);
			// TODO change error code
			throw new KurentoMediaFrameworkException(e.getMessage(), e, 30000);
		}

	}

	/**
	 * Returns the parent of this media object. The type of the parent depends
	 * on the type of the element that this method is called upon. <li>
	 * MediaPad->MediaElement</li> <li>MediaMixer->MediaPipeline</li> <li>
	 * MediaElement->MediaPipeline</li> <li>MediaPipeline->null</li>
	 * 
	 * @return The parent
	 * @throws MediaServerException
	 * @throws InvokationException
	 */
	public <F extends MediaObject> void getParent(final Continuation<F> cont)
			throws KurentoMediaFrameworkException {

		if (parent == null) {
			final AsyncClient client = this.clientPool.acquireAsync();

			try {
				client.getParent(this.objectRef.getThriftRef(),
						new AsyncMethodCallback<AsyncClient.getParent_call>() {

							@Override
							public void onError(Exception exception) {
								clientPool.release(client);
								cont.onError(exception);
							}

							@Override
							public void onComplete(getParent_call response) {
								MediaObjectRefDTO refDTO;

								try {
									refDTO = fromThrift(response.getResult());
								} catch (MediaServerException e) {
									throw new KurentoMediaFrameworkException(e
											.getMessage(), e, e.getErrorCode());
								} catch (TException e) {
									// TODO change error code
									throw new KurentoMediaFrameworkException(e
											.getMessage(), e, 30000);
								} finally {
									clientPool.release(client);
								}

								// TODO check if this cast is ok
								@SuppressWarnings("unchecked")
								F parent = (F) ctx.getBean("mediaObject",
										refDTO);
								cont.onSuccess(parent);
							}
						});
			} catch (TException e) {
				clientPool.release(client);
				// TODO change error code
				throw new KurentoMediaFrameworkException(e.getMessage(), e,
						30000);
			}
		} else {
			// TODO check if this cast is ok
			cont.onSuccess((F) parent);
		}
	}

	/**
	 * Returns the pipeline to which this MediaObject belong, or the pipeline
	 * itself if invoked over a {@link MediaPipeline}
	 * 
	 * @return The media pipeline for the object, or <code>this</code> in case
	 *         of a media pipeline
	 * @throws MediaServerException
	 * @throws InvokationException
	 */
	public void getMediaPipeline(final Continuation<MediaPipeline> cont)
			throws KurentoMediaFrameworkException {
		if (pipeline == null) {
			final AsyncClient client = this.clientPool.acquireAsync();

			try {
				client.getMediaPipeline(
						this.objectRef.getThriftRef(),
						new AsyncMethodCallback<AsyncClient.getMediaPipeline_call>() {

							@Override
							public void onError(Exception exception) {
								clientPool.release(client);
								cont.onError(exception);
							}

							@Override
							public void onComplete(
									getMediaPipeline_call response) {
								MediaPipelineRefDTO objRef;

								try {
									objRef = new MediaPipelineRefDTO(response
											.getResult());
								} catch (MediaServerException e) {
									throw new KurentoMediaFrameworkException(e
											.getMessage(), e, e.getErrorCode());
								} catch (TException e) {
									// TODO change error code
									throw new KurentoMediaFrameworkException(e
											.getMessage(), e, 30000);
								} finally {
									clientPool.release(client);
								}

								MediaPipeline pipeline = (MediaPipeline) ctx
										.getBean("mediaPipeline", objRef);
								cont.onSuccess(pipeline);
							}
						});
			} catch (TException e) {
				clientPool.release(client);
				// TODO change error code
				throw new KurentoMediaFrameworkException(e.getMessage(), e,
						30000);
			}
		} else {
			cont.onSuccess(pipeline);
		}
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == null) {
			return false;
		}

		if (this == obj) {
			return true;
		}

		if (!obj.getClass().equals(this.getClass())) {
			return false;
		}

		MediaObject mo = (MediaObject) obj;
		return mo.objectRef.equals(this.objectRef);
	}

	@Override
	public int hashCode() {
		return this.objectRef.hashCode();
	}
}
