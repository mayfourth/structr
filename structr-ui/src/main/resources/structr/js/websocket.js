/*
 *  Copyright (C) 2010-2013 Axel Morgner, structr <structr@structr.org>
 *
 *  This file is part of structr <http://structr.org>.
 *
 *  structr is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as
 *  published by the Free Software Foundation, either version 3 of the
 *  License, or (at your option) any later version.
 *
 *  structr is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with structr.  If not, see <http://www.gnu.org/licenses/>.
 */


var ws;
var token;
var loggedIn = false;
var user;
var reconn;

var rawResultCount = [];
var pageCount = [];
var page = 1;
var pageSize = 25;
var sort = 'name';
var order = 'asc';

function connect() {

    if (token) {
        loggedIn = true;
    }

    try {
        
        ws = null;
        
        var isEnc = (window.location.protocol == 'https:');
        var host = document.location.host;
        var wsUrl = 'ws' + (isEnc ? 's' : '') + '://' + host + wsRoot;

        log(wsUrl);
        if ('WebSocket' in window) {
            
            ws = new WebSocket(wsUrl, 'structr');
            
        } else if ('MozWebSocket' in window) {
            
            ws = new MozWebSocket(wsUrl, 'structr');
            
        } else {
            
            alert('Your browser doesn\'t support WebSocket.');
            return false;
            
        }

        log('WebSocket.readyState: ' + ws.readyState, ws);
		
        var entityId;
        var entity;

        ws.onopen = function() {
            
            $.unblockUI({
                fadeOut: 25
            });
            
            log('de-activating reconnect loop', reconn);
            window.clearInterval(reconn);

            log('logged in? ' + loggedIn);
            if (!loggedIn) {
                log('no');
                $('#logout_').html('Login');
                Structr.login();
            } else {
                log('Current user: ' + user);
                $('#logout_').html(' Logout <span class="username">' + (user ? user : '') + '</span>');
				
                Structr.loadInitialModule();
				
            }
        }

        ws.onclose = function() {
            
            if (reconn) {
                log('Automatic reconnect already active');
                return;
            }
            
            main.empty();
            //Structr.confirmation('Connection lost or timed out.<br>Reconnect?', Structr.silenctReconnect);
            Structr.reconnectDialog('Connection lost or timed out. Trying to reconnect ...');
            //log('Connection was lost or timed out. Trying automatic reconnect');
            log('ws onclose');
            Structr.reconnect();
            
        }

        ws.onmessage = function(message) {

            log(message);

            var data = $.parseJSON(message.data);
            //console.log(data);

            //var msg = $.parseJSON(message);
            var type = data.data.type;
            var command = data.command;
            var parentId = data.id;
            var entityId = data.data.id;
            var componentId = data.data.componentId;
            var pageId = data.data.pageId;
            var position = data.data.position;
            var msg = data.message;
            var result = data.result;
            var sessionValid = data.sessionValid;
            var code = data.code;
            var callback = data.callback;
            
            rawResultCount[type] = data.rawResultCount;
            pageCount[type] = Math.ceil(rawResultCount[type] / pageSize[type]);
            Structr.updatePager(type);

            {
                log('command: ' + command);
                log('type: ' + type);
                log('code: ' + code);
                log('callback: ' + callback);
                log('sessionValid: ' + sessionValid);
            }
            log('result: ' + $.toJSON(result));

            if (command == 'LOGIN') { /*********************** LOGIN ************************/
                token = data.token;
                user = data.data.username;
                log('token', token);
		
                if (sessionValid) {
                    $.cookie('structrSessionToken', token);
                    $.cookie('structrUser', user);
                    $.unblockUI({
                        fadeOut: 25
                    });
                    $('#logout_').html('Logout <span class="username">' + user + '</span>');

                    Structr.loadInitialModule();
					
                } else {
                    $.cookie('structrSessionToken', '');
                    $.cookie('structrUser', '');
                    clearMain();

                    Structr.login();
                }

            } else if (command == 'LOGOUT') { /*********************** LOGOUT ************************/

                $.cookie('structrSessionToken', '');
                $.cookie('structrUser', '');
                clearMain();
                Structr.login();

            } else if (command == 'STATUS') { /*********************** STATUS ************************/
                log('Error code: ' + code);
				
                if (code == 403) {
                    Structr.login('Wrong username or password!');
                } else if (code == 401) {
                    Structr.login('Session invalid');
                } else {
                    var msgClass;
                    var codeStr = code.toString();
                    if (codeStr.startsWith('20')) {
                        msgClass = 'success';
                        $('#dialogBox .dialogMsg').html('<div class="infoBox ' + msgClass + '">' + msg + '</div>');
                    } else if (codeStr.startsWith('30')) {
                        msgClass = 'info';
                        $('#dialogBox .dialogMsg').html('<div class="infoBox ' + msgClass + '">' + msg + '</div>');
                    } else if (codeStr.startsWith('40')) {
                        msgClass = 'warning';
                        $('#dialogBox .dialogMsg').html('<div class="infoBox ' + msgClass + '">' + msg + '</div>');
                    } else {
                        Structr.error("Error", true);
                        msgClass = 'error';
                        $('#errorBox .errorMsg').html('<div class="infoBox ' + msgClass + '">' + msg + '</div>');
                    }

                }

            } else if (command == 'GET') { /*********************** GET ************************/

                log('GET:', data);

                var d = data.data.displayElementId;
                log('displayElementId', d);

                var parentElement;
                if (d != null) {
                    parentElement = $($(d)[0]);
                } else {
                    parentElement = $($('.' + data.id + '_')[0]);
                }

                log('parentElement', parentElement);
                var key = data.data.key;
                var value = data.data[key];

                var attrElement = $(parentElement.find('.' + key + '_')[0]);
                log('attrElement', attrElement);
                log(key, value);

                if (attrElement && value) {

                    if (typeof value == 'boolean') {
                        log(attrElement, value);
                        _Entities.changeBooleanAttribute(attrElement, value);

                    } else {
                        
                        log($(attrElement));
                        
                        var tag = $(attrElement).get(0).tagName.toLowerCase();
                        
                        log('attrElement tagName', tag);
                        
                        if (!(tag == 'select')) {
                            log('appending ' + value + ' to attrElement', attrElement);
                            attrElement.append(value);
                        }
                        
                        log('setting ' + value + ' on attrElement', attrElement);
                        
                        attrElement.val(value);
                        attrElement.show();
                    }
                }

            } else if (command == 'CHILDREN') { /*********************** CHILDREN ************************/

                var treeAddress = data.data.treeAddress;

                log('CHILDREN:', parentId, componentId, pageId);
                log('CHILDREN');
                log('parentId', parentId);
                log('componentId', componentId);
                log('pageId', pageId);
                log('Nodes with children', data.nodesWithChildren);
                log('Tree address', treeAddress);
                
                
                $(result).each(function(i, child) {
                    log('CHILDREN: ', child, parentId, componentId, pageId, false, isIn(child.id, data.nodesWithChildren), treeAddress);
                    _Entities.appendObj(child, parentId, componentId, pageId, false, isIn(child.id, data.nodesWithChildren), treeAddress);
                });

            } else if (command == 'LIST') { /*********************** LIST ************************/
				
                log('LIST:', result);
                log('Nodes with children', data.nodesWithChildren);
                //console.log('page count for type ' + type, pageCount[type], $('#pager' + type), $('.pageCount', $('#pager' + type)));
                $('.pageCount', $('#pager' + type)).val(pageCount[type]);
                
                $(result).each(function(i, entity) {
                    log('LIST: ' + entity.type);
                    
                    if (entity.type != 'Folder' || !entity.parentFolder) {
                        _Entities.appendObj(entity, null, null, null, false, isIn(entity.id, data.nodesWithChildren), treeAddress);
                    } else {
                        log(entity);
                    }
                    
                });

            } else if (command == 'DELETE') { /*********************** DELETE ************************/
                var elementSelector = '.' + data.id + '_';
                log($(elementSelector));
                $(elementSelector).remove();
                if (buttonClicked) enable(buttonClicked);
                _Pages.reloadPreviews();

            } else if (command == 'REMOVE') { /*********************** REMOVE ************************/

                log(command, data);

                //parent = Structr.node(parentId);
                entity = Structr.node(entityId, parentId, componentId, pageId, position);

                //log(parent);
                log(entity);

                //var id = getIdFromClassString(entity.prop('class'));
                //entity.id = id;
                if (entity.hasClass('user')) {
                    log('remove user from group');
                    _UsersAndGroups.removeUserFromGroup(entityId, parentId, position);

                } else if (entity.hasClass('element') || entity.hasClass('content') || entity.hasClass('component')) {
                    
                    log('remove element from page', entityId, parentId, componentId, pageId, position);
                    _Pages.removeFrom(entityId, parentId, componentId, pageId, position);
                    _Pages.reloadPreviews();

                } else if (entity.hasClass('file')) {
                    log('remove file from folder');
                    _Files.removeFileFromFolder(entityId, parentId, position);

                } else if (entity.hasClass('image')) {
                    log('remove image from folder');
                    _Files.removeImageFromFolder(entityId, parentId, position);

                } else if (entity.hasClass('folder')) {
                    log('remove folder from folder');
                    _Files.removeFolderFromFolder(entityId, parentId, position);

                } else {
                //log('remove element');
                //entity.remove();
                }

                _Pages.reloadPreviews();
                log('Removed ' + entityId + ' from ' + parentId);

            //} else if (command == 'ADD' || command == 'IMPORT') { /*********************** CREATE, ADD, IMPORT ************************/
            } else if (command == 'CREATE' || command == 'ADD' || command == 'IMPORT') { /*********************** CREATE, ADD, IMPORT ************************/
            //} else if (command == 'CREATE' || command == 'IMPORT') { /*********************** CREATE, ADD, IMPORT ************************/
                
                log(command, result, data, data.data);
                
                //var treeAddress = data.data.treeAddress;
				
                $(result).each(function(i, entity) {
                    
                   log(command, entity, parentId, componentId, pageId, command == 'ADD', isIn(entity.id, data.nodesWithChildren), treeAddress);
                    
                    //var el = Structr.node(entity.id, parentId, componentId, pageId);
                    var el = Structr.elementFromAddress(treeAddress);
                    if (el) el.remove();
                    
                    //alert(entity.id);
                    
                    _Entities.appendObj(entity, parentId, componentId, pageId, command == 'ADD', isIn(entity.id, data.nodesWithChildren), treeAddress);
                    
                    if (command == 'CREATE' && entity.type == 'Page') {
                        var tab = $('#show_' + entity.id, previews);
                        setTimeout(function() { _Pages.activateTab(tab) }, 200);
                    }
                    
                });

                _Pages.reloadPreviews();
                
            //alert(command);

            } else if (command == 'UPDATE') { /*********************** UPDATE ************************/
                
                log('UPDATE');
                
                var relData = data.relData;
                log('relData', relData);
                
                var removedProperties = data.removedProperties;
                var modifiedProperties = data.modifiedProperties;
                
                log(removedProperties, modifiedProperties);
                
                var isRelOp = false;
                
                if (relData && relData.startNodeId && relData.endNodeId) {
                    isRelOp = true;
                    log('relationship', relData, relData.startNodeId, relData.endNodeId);
                    
                }
                
                if (modifiedProperties) {
                    log('modifiedProperties.length', modifiedProperties.length);
                    var resId = modifiedProperties[0];
                    log('relData[resId]', relData[resId]);
                }
                
                if (relData && removedProperties && removedProperties.length) {
                    log('removedProperties', removedProperties);
                    _Pages.removeFrom(relData.endNodeId, relData.startNodeId, null, removedProperties[0]);
                    
                } else if (isRelOp && modifiedProperties && modifiedProperties.length) {
                    
                    log(data);
                    
                    log('modifiedProperties', modifiedProperties[0]);
                		    
                    var newPageId = modifiedProperties[0];
                    //var pos = relData[newPageId];
                		    
                    var page;
                        
                    if (newPageId != '*') {
                        page   = Structr.node(newPageId);
                    }
                    
                    log('page', page);
                		    
                    if (page && page.length) {
                                    
                        var entity = Structr.entity(relData.endNodeId, relData.startNodeId);
                        log('entity', entity, pageId, newPageId);
                        if (entity && newPageId) {
                            
                            parentId = relData.startNodeId;
                            
                            var parent = Structr.entity(parentId);
                            log('parent type', parent, parent.type);
                            if (!parent.type || parent.type == 'Page') return;
                            
                            var id = entity.id;
                            //_Pages.removeFrom(entity.id, relData.startNodeId, null, newPageId, pos);
                            //_Entities.appendObj(entity, relData.startNodeId, null, newPageId);
                            var el = Structr.node(id, parentId, componentId, newPageId);
                            log('node already exists?', el);
                            
                            if (id && (!el || !el.length)) {
                                //el.remove();
                            
                                //_Entities.resetMouseOverState(el);
                                _Entities.appendObj(entity, parentId, null, newPageId, true, true);
                            }
                            
                        //_Entities.reloadChildren(relData.startNodeId, componentId, newPageId)
                        
                        //_Pages.refresh();
                        
                        }
                    }
                    
                } else {
                    
                    log('else');
                
                    var element = $('.' + data.id + '_');
//                    var input = $('.props tr td.value input', element);
                    log(element);
//
//                    // remove save and cancel icons
//                    input.parent().children('.icon').each(function(i, img) {
//                        $(img).remove();
//                    });
//
//                    // make inactive
//                    input.removeClass('active');

                    // update values with given key
                    log(data, data.data);
                    $.each(Object.keys(data.data), function(k, key) {
                    //for (var key in data.data) {
                        var inputElement = $('td.' + key + '_ input', element);
                        log(inputElement);
                        var newValue = data.data[key];
//                        console.log(key, newValue, typeof newValue);

                        var attrElement = $('.' + key + '_', element);
                    
                        if (attrElement && $(attrElement).length) {
                    
                            var tag = $(attrElement).get(0).tagName.toLowerCase();
                        
                            attrElement.val(value);
                            attrElement.show();
                    
                            log(attrElement, inputElement);
                    
                        }
                    

                        if (typeof newValue  == 'boolean') {

                            _Entities.changeBooleanAttribute(attrElement, newValue);
                        
                        } else {

                            attrElement.animate({
                                color: '#81ce25'
                            }, 100, function() {
                                $(this).animate({
                                    color: '#333333'
                                }, 200);
                            });
                        
                            if (attrElement && tag == 'select') {
                                attrElement.val(newValue);
                            } else {
                                log(key, newValue);
                                if (key == 'name') {
                                    attrElement.html(fitStringToSize(newValue, 200));
                                    attrElement.attr('title', newValue);
                                }
                            }
                        
                            if (inputElement) {
                                inputElement.val(newValue);
                            }

                            if (key == 'content') {

                                log(attrElement.text(), newValue);

                                //attrElement.text(newValue);

                                // hook for CodeMirror edit areas
                                if (editor && editor.id == data.id) {
                                    log(editor.id);
                                    editor.setValue(newValue);
                                    //editor.setCursor(editorCursor);
                                }
                            }
                        }
                    
                        log(key, Structr.getClass(element));
                    
                        if (key == 'name' && Structr.getClass(element) == 'page') {
                            log('Reload iframe', data.id, newValue);
                            window.setTimeout(function() {
                                _Pages.reloadIframe(data.id, newValue)
                            }, 100);
                        }

                    });
                

                
                }
                
//                if (input) {
//                    input.data('changed', false);
//                }
                
                _Pages.reloadPreviews();
                
            } else if (command == 'WRAP') { /*********************** WRAP ************************/

                log('WRAP');

            } else {
                log('Received unknown command: ' + command);

                if (sessionValid == false) {
                    log('invalid session');
                    $.cookie('structrSessionToken', '');
                    $.cookie('structrUser', '');
                    clearMain();

                    Structr.login();
                }
            }
        }

    } catch (exception) {
        log('Error in connect(): ' + exception);
        //Structr.init();
    }

}

function sendObj(obj) {

    if (token) {
        obj.token = token;
    }

    text = $.toJSON(obj);

    if (!text) {
        log('No text to send!');
        return false;
    }

    try {
        ws.send(text);
        log('Sent: ' + text);
    } catch (exception) {
        log('Error in send(): ' + exception);
        return false;
    }
    return true;
}

function send(text) {

    log(ws.readyState);

    var obj = $.parseJSON(text);

    return sendObj(obj);
}

function log(msg) {
    if (debug) {
        console.log(msg);
        if (footer) {
            var div = $('#log', footer);
            div.append('<p>' + msg + '</p>');
            footer.scrollTop(div.height());
        }
    }
}

function getAnchorFromUrl(url) {
    if (url) {
        var pos = url.lastIndexOf('#');
        if (pos > 0) {
            return url.substring(pos+1, url.length);
        }
    }
    return null;
}


function utf8_to_b64( str ) {
    //return window.btoa(unescape(encodeURIComponent( str )));
    return window.btoa(str);
}

function b64_to_utf8( str ) {
    //return decodeURIComponent(escape(window.atob( str )));
    return window.atob(str);
}