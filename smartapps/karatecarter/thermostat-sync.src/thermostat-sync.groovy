/**
 *  Thermostat Sync
 *
 *  Copyright 2017 Daniel Carter
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
definition(
    name: "Thermostat Sync",
    namespace: "karatecarter",
    author: "Daniel Carter",
    description: "Select one or more source thermostats, and use anyone of them to control one or more target thermostats; set the desired temperature on a source thermostat, and when the most recently used source thermostat turns on, it will trigger the destination thermostat(s) to turn on.",
    category: "Green Living",
    iconUrl: "http://cdn.device-icons.smartthings.com/Home/home1-icn.png",
    iconX2Url: "http://cdn.device-icons.smartthings.com/Home/home1-icn@2x.png",
    iconX3Url: "http://cdn.device-icons.smartthings.com/Home/home1-icn@3x.png")


preferences {
	section("Thermostats") {
		input "sourceThermostats", "capability.thermostat", title: "Choose the source thermostats that will be used to control the destination thermostats", required: true, multiple:true
		input "destThermostats", "capability.thermostat", title: "Choose the destination thermostats to control", required: true, multiple:true
	}
}

def installed() {
	log.debug "Installed with settings: ${settings}"

	initialize()
}

def updated() {
	log.debug "Updated with settings: ${settings}"

	unschedule()
    unsubscribe()
	initialize()
}

def initialize() {
	log.debug "Most recently used thermostat ID: ${state.sourceid}"
    if (state.sourceid == null) {
    	setSource(settings.sourceThermostats[0])
    }
    refreshAndSync()

	subscribe(sourceThermostats, "heatingSetpoint", thermostatSetTempHandler)
    subscribe(sourceThermostats, "coolingSetpoint", thermostatSetTempHandler)
    subscribe(sourceThermostats, "thermostatOperatingState", thermostatOperatingStateHandler)
    subscribe(sourceThermostats, "thermostatMode", thermostatModeHandler)
    subscribe(sourceThermostats, "thermostatFanMode", thermostatModeHandler)
    
}

def thermostatSetTempHandler(evt)
{
    // if setPoint is one of the two extremes, then assume the temp was set remotely
    if (!([minTemp(), maxTemp()].any { it == (evt.value as Integer)})) {
		log.info "Thermostat Set Temp Handler: ${evt.device.displayName} ${evt.name} set to ${evt.value}"
    
        setSource(evt.device)
        syncThermostats(evt.device)

        runIn(60, refreshAndSync) // sync again in 1 minute because operating state doesn't always update correctly
	}
}

def thermostatOperatingStateHandler(evt)
{
	syncThermostats(evt.device)
}

def thermostatModeHandler(evt)
{
	log.info "Thermostat Mode Handler: ${evt.device.displayName} ${evt.name} set to ${evt.value}"
    
    setSource(evt.device)
	syncThermostats(evt.device)

    runIn(60, refreshAndSync) // sync again in 1 minute because operating state doesn't always update correctly

}

def syncThermostats(src)
{
    def operatingState = src.currentThermostatOperatingState
    def fanMode = src.currentThermostatFanMode
    
	log.trace "Sync with thermostat ${src.displayName}: operating state: ${operatingState}, fan mode: $fanMode"
    
    if (src.id == state.sourceid) {
   		switch(operatingState) {
        	case "pending cool":
            case "cooling":
            	setDestSetpoints(src, operatingState, minTemp(), minTemp())
                setFanMode(src, fanMode)
                break
                
            case "idle":
            case "fan only":
            	setDestSetpoints(src, operatingState, maxTemp(), minTemp())
                setFanMode(src, fanMode)
                break
                
            case "pending heat":
            case "heating":
            	setDestSetpoints(src, operatingState, maxTemp(), maxTemp())
               	setFanMode(src, fanMode)
                break
        }
    } else {
    	log.trace "Thermostat is not source"
    }
}

def setSource(source)
{
	if (state.sourceid != source.id)
    {
    	log.debug "Switching source to ${source.displayName}"
    }
    
    state.sourceid = source.id
}

def setDestSetpoints(source, operatingState, coolingSetpoint, heatingSetpoint)
{
	def currentCoolingSetpoint
    def currentHeatingSetpoint
    
    settings.destThermostats.each { thermostat ->
    	if (thermostat.id != source.id) {
        	currentHeatingSetpoint = thermostat.currentValue("heatingSetpoint")
            currentCoolingSetpoint = thermostat.currentValue("coolingSetpoint")
        
            if (currentHeatingSetpoint != heatingSetpoint || currentCoolingSetpoint != coolingSetpoint)
            {
            	log.info "${source.displayName} is ${operatingState}; setting ${thermostat.displayName} cool point to $coolingSetpoint and heat point to $heatingSetpoint"
    		}
            
            if (currentHeatingSetpoint != heatingSetpoint)
            {
                thermostat.setHeatingSetpoint(heatingSetpoint)
            }
            if (currentCoolingSetpoint != coolingSetpoint)
            {
            	thermostat.setCoolingSetpoint(coolingSetpoint)
            }
        }
    }
}

def setFanMode(source, fanMode)
{
	def currentFanMode
    
    settings.destThermostats.each { thermostat ->
    	if (thermostat.id != source.id) {
			currentFanMode = thermostat.currentThermostatFanMode
            
            if (fanMode != currentFanMode)
            {
            	log.info "${source.displayName} fan mode is ${fanMode}; setting fan mode for ${thermostat.displayName}"
                
                switch (fanMode) {
                    case "fanAuto":
                        thermostat.fanAuto()
                        break

                    case "fanOn":
                        thermostat.fanOn()
                        break

                    case "fanCirculate":
                        thermostat.fanCirculate()
                        break

                    default:
                        log.error "Invalid fan mode selected: $fanMode"
                        break
                }
            }
        }
    }
}

def getSourceThermostat(id)
{
	def thermostat
    
    settings.sourceThermostats.each { src ->
        if (src.id == id)
        {
            thermostat = src
            exit
        }
    }
    if (thermostat == null) log.error "Thermostat id: $id not found"
    return(thermostat)
}

def refreshAndSync()
{
	def thermostat = getSourceThermostat(state.sourceid)
    try { 
    	thermostat.refresh()
    } // in case device doesn't support refresh
    catch(e)
    {
    	syncThermostats(thermostat)
    }
}

def minTemp()
{
	def locationScale = getTemperatureScale()
    
    if (locationScale == "C") {
		return(10)
    } else {
	    return(50)
    }
}

def maxTemp()
{
	def locationScale = getTemperatureScale()
    
    if (locationScale == "C") {
		return(32)
    } else {
	    return(90)
    }
}